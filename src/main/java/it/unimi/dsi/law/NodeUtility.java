package it.unimi.dsi.law;

import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.lang.MutableString;
import it.unimi.dsi.sux4j.mph.GOVMinimalPerfectHashFunction;
import it.unimi.dsi.webgraph.EFGraph;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.function.Function;

import static it.unimi.dsi.law.Parameters.*;

public class NodeUtility {
    public static void main(String[] args) throws IOException, ClassNotFoundException {
        GOVMinimalPerfectHashFunction<CharSequence> addressMap = (GOVMinimalPerfectHashFunction<CharSequence>) BinIO.loadObject(addressesMap.toFile());

        if (!addressesInverseMap.toFile().exists()) {
            LoggerFactory.getLogger(NodeUtility.class).info("Computing inverse address map, this might take a while...");
            Iterator<CharSequence> addresses = Iterators.transform(Utils.readTSVs(addressesFile), line -> Utils.column(line, 0));
            buildInverseMap(addressMap, addresses, addressesInverseMap);
            LoggerFactory.getLogger(NodeUtility.class).info("Done!");
        }

        Long2ObjectOpenHashMap<String> addressInverseMap = (Long2ObjectOpenHashMap<String>) BinIO.loadObject(addressesInverseMap.toFile());

        EFGraph bitcoinGraph = EFGraph.load(basename.toString());
        long[] addressIds = BinIO.loadLongs(ids.toFile());

        LoggerFactory.getLogger(NodeUtility.class).info("This only works with addresses contained in addresses.tsv, otherwise the results are unpredictable.");
        System.out.print("address> ");
        String address = new Scanner(System.in).nextLine();

        long id = addressMap.getLong(address);
        int node = ArrayUtils.indexOf(addressIds, id);

        int[] successors = bitcoinGraph.successorArray(node);

        System.out.println(address + " (id: " + id + ", outdegree: " + bitcoinGraph.outdegree(node) + "):");
        for(int i = 0; i < bitcoinGraph.outdegree(node); i++) {
            long successorId = addressIds[successors[i]];
            System.out.println("\t" + addressInverseMap.get(successorId) + " (id: " +  successorId + ")");
        }
    }

    private static void buildInverseMap(GOVMinimalPerfectHashFunction<CharSequence> map, Iterator<CharSequence> iterator, Path addressesInverseMap) throws IOException {
        Long2ObjectOpenHashMap<String> inverse = new Long2ObjectOpenHashMap<>();
        iterator.forEachRemaining(line -> inverse.put(map.getLong(line), line.toString()));
        inverse.trim();
        BinIO.storeObject(inverse, addressesInverseMap.toFile());
    }
}
