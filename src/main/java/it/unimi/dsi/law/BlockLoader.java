package it.unimi.dsi.law;

import it.unimi.dsi.logging.ProgressLogger;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Utils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class BlockLoader implements Runnable {
    private final Iterator<File> blockFiles;
    private final LinkedBlockingQueue<List<byte[]>> blockQueue;
    private final ProgressLogger progress;
    private final NetworkParameters np;

    public BlockLoader(List<File> blockFiles, LinkedBlockingQueue<List<byte[]>> blockQueue, ProgressLogger progress, NetworkParameters np) {
        this.blockFiles = blockFiles.iterator();
        this.blockQueue = blockQueue;
        this.progress = progress;
        this.np = np;
    }

    public List<byte[]> loadNextBlocks() throws IOException {
        if (!blockFiles.hasNext())
            return null;

        File blockFile = blockFiles.next();
        byte[] blocks = Files.readAllBytes(blockFile.toPath());
        ByteArrayInputStream bis = new ByteArrayInputStream(blocks);
        List<byte[]> blockList = new ArrayList<>();

        while (true) {
            int nextChar = bis.read();
            while (nextChar != -1) {
                if (nextChar != ((np.getPacketMagic() >>> 24) & 0xff)) {
                    nextChar = bis.read();
                    continue;
                }
                nextChar = bis.read();
                if (nextChar != ((np.getPacketMagic() >>> 16) & 0xff))
                    continue;
                nextChar = bis.read();
                if (nextChar != ((np.getPacketMagic() >>> 8) & 0xff))
                    continue;
                nextChar = bis.read();
                if (nextChar == (np.getPacketMagic() & 0xff))
                    break;
            }

            byte[] bytes = new byte[4];
            if (bis.read(bytes, 0, 4) == -1)
                break;

            long size = Utils.readUint32BE(Utils.reverseBytes(bytes), 0);

            bytes = new byte[(int) size];
            if (bis.read(bytes, 0, (int) size) == -1)
                break;

            blockList.add(bytes);
        }

        this.progress.logger.info("New block file loaded " + blockFile);

        return blockList;
    }

    @Override
    public void run() {
        while (true) {
            while (blockQueue.size() > 1)
                Thread.yield();

            try {
                List<byte[]> blocks = loadNextBlocks();

                if (blocks == null)
                    break;

                if (blocks.size() > 0)
                    blockQueue.add(blocks);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}