package it.unimi.dsi.law;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.util.BloomFilter;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;

import static it.unimi.dsi.law.Parameters.CleanedBitcoinColumn.TRANSACTION_HASH;

public class TransactionBloom {
	public static void main (String[] args) throws IOException {
		Parameters.filtersDirectory.toFile().mkdir();
		File[] outputs = Parameters.outputsDirectory.toFile().listFiles((d, f) -> f.endsWith("tsv"));

		if (outputs == null) {
			throw new FileNotFoundException("No outputs found!");
		}

		for (File output : outputs) {
			save(output);
		}

		System.out.println("Filters saved in " + Parameters.filtersDirectory);
	}

	public static void save (File output) throws IOException {
		BloomFilter<CharSequence> transactionFilter = BloomFilter.create(1000, BloomFilter.STRING_FUNNEL);

		FileReader originalReader = new FileReader(output);
		CSVParser tsvParser = new CSVParserBuilder().withSeparator('\t').build();
		CSVReader tsvReader = new CSVReaderBuilder(originalReader).withCSVParser(tsvParser).build();

		tsvReader.readNext(); // header
		tsvReader.iterator().forEachRemaining(line -> transactionFilter.add(line[TRANSACTION_HASH].getBytes()));

		BinIO.storeObject(transactionFilter, Parameters.filtersDirectory.resolve(output.getName()).toFile());
	}
}
