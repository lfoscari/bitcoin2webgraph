package it.unimi.dsi.law;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.law.persistence.PersistenceLayer;
import it.unimi.dsi.logging.ProgressLogger;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.utils.ContextPropagatingThreadFactory;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.*;

public class CustomBlockchainIterator implements Iterator<long[]>, Iterable<long[]> {
    private final NetworkParameters np;
    private final ProgressLogger progress;
    private final AddressConversion addressConversion;
    private PersistenceLayer mappings;

    private final LinkedBlockingQueue<Long[]> transactionArcs;
    private final LinkedBlockingQueue<List<byte[]>> blockQueue;
    private final LinkedBlockingQueue<WriteBatch> wbQueue;

    private final List<File> blockFiles;
    private ExecutorService blockchainParsers;
    private ExecutorService diskHandlers;

    public CustomBlockchainIterator(List<File> blockFiles, AddressConversion addressConversion, NetworkParameters np, ProgressLogger progress) {
        this.np = np;
        this.progress = progress;
        this.addressConversion = addressConversion;

        this.transactionArcs = new LinkedBlockingQueue<>();
        this.blockQueue = new LinkedBlockingQueue<>(Parameters.numberOfThreads / 2);
        this.wbQueue = new LinkedBlockingQueue<>(Parameters.numberOfThreads / 2);

        this.blockFiles = blockFiles;
    }

    public void populateMappings() throws RocksDBException, InterruptedException, ExecutionException {
        this.progress.start("Populating mappings with " + Parameters.numberOfThreads + " threads");

        this.mappings = new PersistenceLayer(Parameters.resources + "bitcoin-db", false);

        WriteBatch stopWb = new WriteBatch();

        this.diskHandlers = Executors.newFixedThreadPool(2, new ContextPropagatingThreadFactory("disk-handler"));
        Future<?> loaderStatus = this.diskHandlers.submit(new BlockLoader(this.blockFiles, blockQueue, wbQueue, progress, np));
        Future<?> writerStatus = this.diskHandlers.submit(new DBWriter(this.mappings, wbQueue, stopWb, progress));
        diskHandlers.shutdown();

        this.blockchainParsers = Executors.newFixedThreadPool(Parameters.numberOfThreads, new ContextPropagatingThreadFactory("populating-mappings"));
        List<Future<?>> pmTasks = new ArrayList<>();

        while (!loaderStatus.isDone()) {

            // Manage the number of active PopulateMappings tasks to avoid thrashing
            if (pmTasks.size() > Parameters.numberOfThreads) {
                // Remove done threads
                pmTasks.removeIf(Future::isDone);
                System.gc();
                continue;
            }

            List<byte[]> blocksBytes = blockQueue.take();

            this.progress.logger.info("New mapping task added");
            PopulateMappings pm = new PopulateMappings(blocksBytes, addressConversion, transactionArcs, mappings.getColumnFamilyHandleList(), wbQueue, np, progress);
            Future<?> pmFuture = this.blockchainParsers.submit(pm);
            pmTasks.add(pmFuture);
        }

        this.blockchainParsers.shutdown();

        for (Future<?> pmFuture : pmTasks)
            pmFuture.get();

        wbQueue.put(stopWb);
        this.diskHandlers.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        this.blockchainParsers.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

        this.mappings.close();
        this.progress.stop();
    }

    public void completeMappings() throws RocksDBException, InterruptedException {
        this.progress.start("Completing mappings with " + Parameters.numberOfThreads + " threads");

        this.mappings = new PersistenceLayer(Parameters.resources + "bitcoin-db", true);

        this.diskHandlers = Executors.newFixedThreadPool(2, new ContextPropagatingThreadFactory("disk-handler"));
        Future<?> loaderStatus = this.diskHandlers.submit(new BlockLoader(this.blockFiles, blockQueue, null, progress, np));
        diskHandlers.shutdown();

        this.blockchainParsers = Executors.newFixedThreadPool(Parameters.numberOfThreads, new ContextPropagatingThreadFactory("completing-mappings"));
        List<Future<?>> cmTasks = new ArrayList<>();

        while (!loaderStatus.isDone()) {

            // Manage the number of active PopulateMappings tasks to avoid thrashing
            if (cmTasks.size() > Parameters.numberOfThreads) {
                // Remove done threads
                cmTasks.removeIf(Future::isDone);
                System.gc();
                continue;
            }

            List<byte[]> block = blockQueue.take();

            this.progress.logger.info("New mapping task added");
            CompleteMappings cm = new CompleteMappings(block, addressConversion, transactionArcs, mappings, np, progress);
            Future<?> cmFuture = this.blockchainParsers.submit(cm);
            cmTasks.add(cmFuture);
        }

        this.blockchainParsers.shutdown();
    }

    @Override
    public boolean hasNext() {
        while (transactionArcs.isEmpty()) {
            if (blockchainParsers.isTerminated()) {
                this.mappings.close();
                this.progress.done();
                return false;
            }
        }

        return true;
    }

    @Override
    public long[] next() {
        try {
            if (transactionArcs.isEmpty())
                throw new NoSuchElementException();

            Long[] arcs = transactionArcs.take();
            return new long[]{arcs[0], arcs[1]};
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterator<long[]> iterator() {
        return this;
    }

    public static List<Long> outputAddressesToLongs(Transaction t, AddressConversion ac, NetworkParameters np) throws RocksDBException {
        LongList outputs = new LongArrayList();

        for (TransactionOutput to : t.getOutputs()) {
            Address receiver = transactionOutputToAddress(to, np);
            outputs.add(receiver == null ? -1 : ac.map(receiver));
        }

        return outputs;
    }

    public static Address transactionOutputToAddress(TransactionOutput to, NetworkParameters np) {
        try {
            Script script = to.getScriptPubKey();

            if (script.getScriptType() == null) {
                // No public keys are contained in this script.
                return null;
            }

            return script.getToAddress(np, true);
        } catch (IllegalArgumentException | ScriptException e) {
            // Non-standard address
            return null;
        }
    }
}