package it.unimi.dsi.law;

import com.google.common.collect.Iterables;
import it.unimi.dsi.bits.TransformationStrategies;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.lang.MutableString;
import it.unimi.dsi.sux4j.mph.GOVMinimalPerfectHashFunction;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Iterator;
import java.util.function.Function;

import static it.unimi.dsi.law.Parameters.*;

public class MappingTables {
    public static GOVMinimalPerfectHashFunction<CharSequence> buildAddressesMap() throws IOException {
        if (addressesMap.toFile().exists()) {
            try {
                LoggerFactory.getLogger(MappingTables.class).info("Loading addresses mappings from memory");
                return (GOVMinimalPerfectHashFunction<CharSequence>) BinIO.loadObject(addressesMap.toFile());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        LoggerFactory.getLogger(MappingTables.class).info("Computing addresses mappings");
        Iterator<MutableString> addressesIt = Utils.readTSVs(addressesFile.toFile(), new MutableString());

        File tempDir = Files.createTempDirectory(resources, "map_temp_").toFile();
        tempDir.deleteOnExit();

        // redundant
        Function<MutableString, CharSequence> extractAddress = line -> Utils.column(line, 0);
        GOVMinimalPerfectHashFunction<CharSequence> map = buildMap(Iterables.transform(() -> addressesIt, extractAddress::apply), tempDir);

        BinIO.storeObject(map, addressesMap.toFile());
        return map;
    }

    public static GOVMinimalPerfectHashFunction<CharSequence> buildTransactionsMap() throws IOException {
        if (transactionsMap.toFile().exists()) {
            LoggerFactory.getLogger(MappingTables.class).info("Loading transactions mappings from memory");
            try {
                return (GOVMinimalPerfectHashFunction<CharSequence>) BinIO.loadObject(transactionsMap.toFile());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        LoggerFactory.getLogger(MappingTables.class).info("Computing transactions mappings");
        Utils.LineFilter filter = (line) -> Utils.columnEquals(line, 7, "0");
        File[] sources = transactionsDirectory.toFile().listFiles((d, s) -> s.endsWith(".tsv"));
        if (sources == null) {
            throw new NoSuchFileException("No transactions found in " + transactionsDirectory);
        }
        Iterator<MutableString> transactionsIt = Utils.readTSVs(sources, new MutableString(), filter);

        File tempDir = Files.createTempDirectory(resources, "map_temp_").toFile();
        tempDir.deleteOnExit();
        Function<MutableString, CharSequence> extractTransactionHash = line -> Utils.column(line, 1);

        GOVMinimalPerfectHashFunction<CharSequence> map = buildMap(Iterables.transform(() -> transactionsIt, extractTransactionHash::apply), tempDir);
        BinIO.storeObject(map, transactionsMap.toFile());
        return map;
    }

    private static GOVMinimalPerfectHashFunction<CharSequence> buildMap(Iterable<CharSequence> it, File tempDir) throws IOException {
        GOVMinimalPerfectHashFunction.Builder<CharSequence> b = new GOVMinimalPerfectHashFunction.Builder<>();
        b.keys(it);
        b.tempDir(tempDir);
        b.transform(TransformationStrategies.iso());
        GOVMinimalPerfectHashFunction<CharSequence> map = b.build();

        return map;
    }

    public static void main(String[] args) throws IOException {
        MappingTables.buildAddressesMap();
        MappingTables.buildTransactionsMap();
    }
}
