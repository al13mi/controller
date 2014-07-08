/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.sample.toaster.provider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.opendaylight.controller.config.yang.config.toaster_provider.impl.ToasterProviderRuntimeMXBean;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.md.sal.binding.api.DataChangeListener;
import org.opendaylight.controller.md.sal.binding.api.ReadWriteTransaction;
import org.opendaylight.controller.md.sal.binding.api.WriteTransaction;
import org.opendaylight.controller.md.sal.common.api.TransactionStatus;
import org.opendaylight.controller.md.sal.common.api.data.AsyncDataChangeEvent;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.OptimisticLockFailedException;
import org.opendaylight.controller.sal.binding.api.NotificationProviderService;
import org.opendaylight.controller.sal.common.util.RpcErrors;
import org.opendaylight.controller.sal.common.util.Rpcs;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.DisplayString;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.MakeToastInput;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.RestockToasterInput;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.Toaster;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.Toaster.ToasterStatus;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.ToasterBuilder;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.ToasterOutOfBreadBuilder;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.ToasterRestocked;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.ToasterRestockedBuilder;
import org.opendaylight.yang.gen.v1.http.netconfcentral.org.ns.toaster.rev091120.ToasterService;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcError.ErrorSeverity;
import org.opendaylight.yangtools.yang.common.RpcError.ErrorType;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class OpendaylightToaster implements ToasterService, ToasterProviderRuntimeMXBean,
                                            DataChangeListener, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OpendaylightToaster.class);

    public static final InstanceIdentifier<Toaster> TOASTER_IID = InstanceIdentifier.builder(Toaster.class).build();

    private static final DisplayString TOASTER_MANUFACTURER = new DisplayString("Opendaylight");
    private static final DisplayString TOASTER_MODEL_NUMBER = new DisplayString("Model 1 - Binding Aware");

    private NotificationProviderService notificationProvider;
    private DataBroker dataProvider;

    private final ExecutorService executor;

    // The following holds the Future for the current make toast task.
    // This is used to cancel the current toast.
    private final AtomicReference<Future<?>> currentMakeToastTask = new AtomicReference<>();

    private final AtomicLong amountOfBreadInStock = new AtomicLong( 100 );

    private final AtomicLong toastsMade = new AtomicLong(0);

    // Thread safe holder for our darkness multiplier.
    private final AtomicLong darknessFactor = new AtomicLong( 1000 );

    public OpendaylightToaster() {
        executor = Executors.newFixedThreadPool(1);
    }

    public void setNotificationProvider(final NotificationProviderService salService) {
        this.notificationProvider = salService;
    }

    public void setDataProvider(final DataBroker salDataProvider) {
        this.dataProvider = salDataProvider;
        setToasterStatusUp( null );
    }

    /**
     * Implemented from the AutoCloseable interface.
     */
    @Override
    public void close() throws ExecutionException, InterruptedException {
        // When we close this service we need to shutdown our executor!
        executor.shutdown();

        if (dataProvider != null) {
            WriteTransaction t = dataProvider.newWriteOnlyTransaction();
            t.delete(LogicalDatastoreType.OPERATIONAL,TOASTER_IID);
            ListenableFuture<RpcResult<TransactionStatus>> future = t.commit();
            Futures.addCallback( future, new FutureCallback<RpcResult<TransactionStatus>>() {
                @Override
                public void onSuccess( RpcResult<TransactionStatus> result ) {
                    LOG.debug( "Delete Toaster commit result: " + result );
                }

                @Override
                public void onFailure( Throwable t ) {
                    LOG.error( "Delete of Toaster failed", t );
                }
            } );
        }
    }

    private Toaster buildToaster( ToasterStatus status ) {

        // note - we are simulating a device whose manufacture and model are
        // fixed (embedded) into the hardware.
        // This is why the manufacture and model number are hardcoded.
        return new ToasterBuilder().setToasterManufacturer( TOASTER_MANUFACTURER )
                                   .setToasterModelNumber( TOASTER_MODEL_NUMBER )
                                   .setToasterStatus( status )
                                   .build();
    }

    /**
     * Implemented from the DataChangeListener interface.
     */
    @Override
    public void onDataChanged( final AsyncDataChangeEvent<InstanceIdentifier<?>, DataObject> change ) {
        DataObject dataObject = change.getUpdatedSubtree();
        if( dataObject instanceof Toaster )
        {
            Toaster toaster = (Toaster) dataObject;
            Long darkness = toaster.getDarknessFactor();
            if( darkness != null )
            {
                darknessFactor.set( darkness );
            }
        }
    }

    /**
     * RPC call implemented from the ToasterService interface that cancels the current
     * toast, if any.
     */
    @Override
    public Future<RpcResult<Void>> cancelToast() {

        Future<?> current = currentMakeToastTask.getAndSet( null );
        if( current != null ) {
            current.cancel( true );
        }

        // Always return success from the cancel toast call.
        return Futures.immediateFuture( Rpcs.<Void> getRpcResult( true,
                                        Collections.<RpcError>emptyList() ) );
    }

    /**
     * RPC call implemented from the ToasterService interface that attempts to make toast.
     */
    @Override
    public Future<RpcResult<Void>> makeToast(final MakeToastInput input) {
        LOG.info("makeToast: " + input);

        final SettableFuture<RpcResult<Void>> futureResult = SettableFuture.create();

        checkStatusAndMakeToast( input, futureResult );

        return futureResult;
    }

    private List<RpcError> makeToasterOutOfBreadError() {
        return Arrays.asList(
                RpcErrors.getRpcError( "out-of-stock", "resource-denied", null, null,
                                       "Toaster is out of bread",
                                       ErrorType.APPLICATION, null ) );
    }

    private List<RpcError> makeToasterInUseError() {
        return Arrays.asList(
            RpcErrors.getRpcError( "", "in-use", null, ErrorSeverity.WARNING,
                                   "Toaster is busy", ErrorType.APPLICATION, null ) );
    }

    private void checkStatusAndMakeToast( final MakeToastInput input,
                                          final SettableFuture<RpcResult<Void>> futureResult ) {

        // Read the ToasterStatus and, if currently Up, try to write the status to Down.
        // If that succeeds, then we essentially have an exclusive lock and can proceed
        // to make toast.

        final ReadWriteTransaction tx = dataProvider.newReadWriteTransaction();
        ListenableFuture<Optional<DataObject>> readFuture =
                                          tx.read( LogicalDatastoreType.OPERATIONAL, TOASTER_IID );

        final ListenableFuture<RpcResult<TransactionStatus>> commitFuture =
            Futures.transform( readFuture, new AsyncFunction<Optional<DataObject>,
                                                                   RpcResult<TransactionStatus>>() {

                @Override
                public ListenableFuture<RpcResult<TransactionStatus>> apply(
                        Optional<DataObject> toasterData ) throws Exception {

                    ToasterStatus toasterStatus = ToasterStatus.Up;
                    if( toasterData.isPresent() ) {
                        toasterStatus = ((Toaster)toasterData.get()).getToasterStatus();
                    }

                    LOG.debug( "Read toaster status: {}", toasterStatus );

                    if( toasterStatus == ToasterStatus.Up ) {

                        if( outOfBread() ) {
                            LOG.debug( "Toaster is out of bread" );

                            return Futures.immediateFuture( Rpcs.<TransactionStatus>getRpcResult(
                                       false, null, makeToasterOutOfBreadError() ) );
                        }

                        LOG.debug( "Setting Toaster status to Down" );

                        // We're not currently making toast - try to update the status to Down
                        // to indicate we're going to make toast. This acts as a lock to prevent
                        // concurrent toasting.
                        tx.put( LogicalDatastoreType.OPERATIONAL, TOASTER_IID,
                                buildToaster( ToasterStatus.Down ) );
                        return tx.commit();
                    }

                    LOG.debug( "Oops - already making toast!" );

                    // Return an error since we are already making toast. This will get
                    // propagated to the commitFuture below which will interpret the null
                    // TransactionStatus in the RpcResult as an error condition.
                    return Futures.immediateFuture( Rpcs.<TransactionStatus>getRpcResult(
                            false, null, makeToasterInUseError() ) );
                }
        } );

        Futures.addCallback( commitFuture, new FutureCallback<RpcResult<TransactionStatus>>() {
            @Override
            public void onSuccess( RpcResult<TransactionStatus> result ) {
                if( result.getResult() == TransactionStatus.COMMITED  ) {

                    // OK to make toast
                    currentMakeToastTask.set( executor.submit(
                                                    new MakeToastTask( input, futureResult ) ) );
                } else {

                    LOG.debug( "Setting error result" );

                    // Either the transaction failed to commit for some reason or, more likely,
                    // the read above returned ToasterStatus.Down. Either way, fail the
                    // futureResult and copy the errors.

                    futureResult.set( Rpcs.<Void>getRpcResult( false, null, result.getErrors() ) );
                }
            }

            @Override
            public void onFailure( Throwable ex ) {
                if( ex instanceof OptimisticLockFailedException ) {

                    // Another thread is likely trying to make toast simultaneously and updated the
                    // status before us. Try reading the status again - if another make toast is
                    // now in progress, we should get ToasterStatus.Down and fail.

                    LOG.debug( "Got OptimisticLockFailedException - trying again" );

                    checkStatusAndMakeToast( input, futureResult );

                } else {

                    LOG.error( "Failed to commit Toaster status", ex );

                    // Got some unexpected error so fail.
                    futureResult.set( Rpcs.<Void> getRpcResult( false, null, Arrays.asList(
                        RpcErrors.getRpcError( null, null, null, ErrorSeverity.ERROR,
                                               ex.getMessage(),
                                               ErrorType.APPLICATION, ex ) ) ) );
                }
            }
        } );
    }

    /**
     * RestConf RPC call implemented from the ToasterService interface.
     * Restocks the bread for the toaster, resets the toastsMade counter to 0, and sends a
     * ToasterRestocked notification.
     */
    @Override
    public Future<RpcResult<java.lang.Void>> restockToaster(final RestockToasterInput input) {
        LOG.info( "restockToaster: " + input );

        amountOfBreadInStock.set( input.getAmountOfBreadToStock() );

        if( amountOfBreadInStock.get() > 0 ) {
            ToasterRestocked reStockedNotification = new ToasterRestockedBuilder()
                .setAmountOfBread( input.getAmountOfBreadToStock() ).build();
            notificationProvider.publish( reStockedNotification );
        }

        return Futures.immediateFuture(Rpcs.<Void> getRpcResult(true, Collections.<RpcError>emptyList()));
    }

    /**
     * JMX RPC call implemented from the ToasterProviderRuntimeMXBean interface.
     */
    @Override
    public void clearToastsMade() {
        LOG.info( "clearToastsMade" );
        toastsMade.set( 0 );
    }

    /**
     * Accesssor method implemented from the ToasterProviderRuntimeMXBean interface.
     */
    @Override
    public Long getToastsMade() {
        return toastsMade.get();
    }

    private void setToasterStatusUp( final Function<Boolean,Void> resultCallback ) {

        WriteTransaction tx = dataProvider.newWriteOnlyTransaction();
        tx.put( LogicalDatastoreType.OPERATIONAL,TOASTER_IID, buildToaster( ToasterStatus.Up ) );

        ListenableFuture<RpcResult<TransactionStatus>> commitFuture = tx.commit();

        Futures.addCallback( commitFuture, new FutureCallback<RpcResult<TransactionStatus>>() {
            @Override
            public void onSuccess( RpcResult<TransactionStatus> result ) {
                if( result.getResult() != TransactionStatus.COMMITED ) {
                    LOG.error( "Failed to update toaster status: " + result.getErrors() );
                }

                notifyCallback( result.getResult() == TransactionStatus.COMMITED );
            }

            @Override
            public void onFailure( Throwable t ) {
                // We shouldn't get an OptimisticLockFailedException (or any ex) as no
                // other component should be updating the operational state.
                LOG.error( "Failed to update toaster status", t );

                notifyCallback( false );
            }

            void notifyCallback( boolean result ) {
                if( resultCallback != null ) {
                    resultCallback.apply( result );
                }
            }
        } );
    }

    private boolean outOfBread()
    {
        return amountOfBreadInStock.get() == 0;
    }

    private class MakeToastTask implements Callable<Void> {

        final MakeToastInput toastRequest;
        final SettableFuture<RpcResult<Void>> futureResult;

        public MakeToastTask( final MakeToastInput toastRequest,
                              final SettableFuture<RpcResult<Void>> futureResult ) {
            this.toastRequest = toastRequest;
            this.futureResult = futureResult;
        }

        @Override
        public Void call() {
            try
            {
                // make toast just sleeps for n seconds per doneness level.
                long darknessFactor = OpendaylightToaster.this.darknessFactor.get();
                Thread.sleep(darknessFactor * toastRequest.getToasterDoneness());

            }
            catch( InterruptedException e ) {
                LOG.info( "Interrupted while making the toast" );
            }

            toastsMade.incrementAndGet();

            amountOfBreadInStock.getAndDecrement();
            if( outOfBread() ) {
                LOG.info( "Toaster is out of bread!" );

                notificationProvider.publish( new ToasterOutOfBreadBuilder().build() );
            }

            // Set the Toaster status back to up - this essentially releases the toasting lock.
            // We can't clear the current toast task nor set the Future result until the
            // update has been committed so we pass a callback to be notified on completion.

            setToasterStatusUp( new Function<Boolean,Void>() {
                @Override
                public Void apply( Boolean result ) {

                    currentMakeToastTask.set( null );

                    LOG.debug("Toast done");

                    futureResult.set( Rpcs.<Void>getRpcResult( true, null,
                                                          Collections.<RpcError>emptyList() ) );

                    return null;
                }
            } );

            return null;
        }
    }
}
