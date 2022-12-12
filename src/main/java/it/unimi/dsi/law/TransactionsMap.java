package it.unimi.dsi.law;

import it.unimi.dsi.fastutil.ints.Int2LongFunction;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.logging.ProgressLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static it.unimi.dsi.law.Parameters.*;
import static it.unimi.dsi.law.Utils.*;

public class TransactionsMap {
	void compute() throws IOException {
		Int2LongFunction transactionMap = new Int2LongOpenHashMap();
		long count = 0;

		Logger logger = LoggerFactory.getLogger(Blockchain2Webgraph.class);
		ProgressLogger progress = new ProgressLogger(logger, Parameters.logInterval, Parameters.logTimeUnit, "transactions");
		progress.start("Building transaction to long map");

		File[] inputs = inputsDirectory.toFile().listFiles((d, s) -> s.endsWith("tsv"));

		if (inputs == null) {
			throw new NoSuchFileException("Download inputs first");
		}

		Iterable<String[]> transactions = Utils.readTSVs(
			inputs, (line) -> true,
			(line) -> new String[] { line[BitcoinColumn.SPENDING_TRANSACTION_HASH] },
			true, null);

		for (String[] transactionLine : transactions) {
			int transaction = transactionLine[0].hashCode();

			if (!transactionMap.containsKey(transaction)) {
				transactionMap.put(transaction, count++);
				progress.lightUpdate();
			}
		}

		progress.start("Saving transactions (total " + count + " transactions)");
		BinIO.storeObject(transactionMap, transactionsMapFile.toFile());
		progress.stop("Map saved in " + transactionsMapFile);
	}

	public static void main (String[] args) throws IOException {
		new TransactionsMap().compute();
	}
}
