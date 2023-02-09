package it.unimi.dsi.law;

import com.google.common.collect.Iterables;
import it.unimi.dsi.bits.TransformationStrategies;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.lang.MutableString;
import it.unimi.dsi.sux4j.mph.GOVMinimalPerfectHashFunction;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.function.Function;

import static it.unimi.dsi.law.Parameters.*;

public class MappingTables {
    public static GOVMinimalPerfectHashFunction<CharSequence> buildAddressesMap() throws IOException {
        artifacts.toFile().mkdir();

        if (addressesMap.toFile().exists()) {
            try {
                LoggerFactory.getLogger(MappingTables.class).info("Loading addresses mappings from memory");
                return (GOVMinimalPerfectHashFunction<CharSequence>) BinIO.loadObject(addressesMap.toFile());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        LoggerFactory.getLogger(MappingTables.class).info("Computing addresses mappings");

        Function<MutableString, CharSequence> extractAddress = line -> Utils.column(line, 0);
        Iterable<CharSequence> addressesTransformed = Iterables.transform(() -> Utils.readTSVs(addressesFile), extractAddress::apply);

        GOVMinimalPerfectHashFunction<CharSequence> map = buildMap(addressesTransformed, addressesMap);
        buildInverseMap(map, Utils.readTSVs(addressesFile), extractAddress, addressesInverseMap);

        return map;
    }

    public static GOVMinimalPerfectHashFunction<CharSequence> buildTransactionsMap() throws IOException {
        artifacts.toFile().mkdir();

        if (transactionsMap.toFile().exists()) {
            LoggerFactory.getLogger(MappingTables.class).info("Loading transactions mappings from memory");
            try {
                return (GOVMinimalPerfectHashFunction<CharSequence>) BinIO.loadObject(transactionsMap.toFile());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        LoggerFactory.getLogger(MappingTables.class).info("Computing transactions mappings");
        Utils.LineFilter filter = (line) -> Utils.column(line, 7).equals("0");
        File[] sources = transactionsDirectory.toFile().listFiles((d, s) -> s.endsWith(".tsv"));
        if (sources == null) {
            throw new NoSuchFileException("No transactions found in " + transactionsDirectory);
        }

        Function<MutableString, CharSequence> extractTransactionHash = line -> Utils.column(line, 1);
        Iterator<MutableString> transactionsIt = Utils.readTSVs(sources, new MutableString(), filter);
        Iterable<CharSequence> transactionsTransformed = Iterables.transform(() -> transactionsIt, extractTransactionHash::apply);

        GOVMinimalPerfectHashFunction<CharSequence> map = buildMap(transactionsTransformed, transactionsMap);
        // Don't need the transaction map for now
        // buildInverseMap(map, Utils.readTSVs(sources, new MutableString(), filter), extractTransactionHash, transactionsMapInverse);

        return map;
    }

    private static void buildInverseMap(GOVMinimalPerfectHashFunction<CharSequence> map, Iterator<MutableString> iterator, Function<MutableString, CharSequence> transformation, Path addressesInverseMap) throws IOException {
        Long2ObjectOpenHashMap<String> inverse = new Long2ObjectOpenHashMap<>();
        Iterables.transform(() -> iterator, transformation::apply)
                .forEach((line) -> inverse.put(map.getLong(line), line.toString()));

        inverse.trim();
        BinIO.storeObject(inverse, addressesInverseMap.toFile());
    }

    private static GOVMinimalPerfectHashFunction<CharSequence> buildMap(Iterable<CharSequence> transactionsTransformed, Path transactionsMap) throws IOException {
        File tempDir = Files.createTempDirectory(resources, "map_temp_").toFile();
        tempDir.deleteOnExit();

        GOVMinimalPerfectHashFunction.Builder<CharSequence> b = new GOVMinimalPerfectHashFunction.Builder<>();
        b.keys(transactionsTransformed);
        b.tempDir(tempDir);
        b.transform(TransformationStrategies.iso());
        GOVMinimalPerfectHashFunction<CharSequence> map = b.build();
        BinIO.storeObject(map, transactionsMap.toFile());

        return map;
    }

    public static void main(String[] args) throws IOException {
        MappingTables.buildAddressesMap();
        MappingTables.buildTransactionsMap();
    }
}
