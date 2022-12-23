package it.unimi.dsi.law;

import it.unimi.dsi.Util;
import it.unimi.dsi.lang.MutableString;
import it.unimi.dsi.logging.ProgressLogger;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Iterator;
import java.util.List;

import static it.unimi.dsi.law.Parameters.*;
import static it.unimi.dsi.law.Parameters.BitcoinColumn.*;
import static it.unimi.dsi.law.RocksDBWrapper.Column.INPUT;
import static it.unimi.dsi.law.RocksDBWrapper.Column.OUTPUT;
import static it.unimi.dsi.law.Utils.*;

public class TransactionsDatabase {
	private final ProgressLogger progress;

	public TransactionsDatabase () {
		this(null);
	}

	public TransactionsDatabase (ProgressLogger progress) {
		if (progress == null) {
			Logger logger = LoggerFactory.getLogger(Blockchain2Webgraph.class);
			progress = new ProgressLogger(logger, logInterval, logTimeUnit, "sources");
			progress.displayLocalSpeed = true;
		}

		this.progress = progress;
	}

	void compute () throws IOException, RocksDBException {
		try (RocksDBWrapper database = new RocksDBWrapper(false, transactionsDatabaseDirectory)) {
			this.progress.start("Building input transactions database");

			{
				File[] sources = inputsDirectory.toFile().listFiles((d, s) -> s.endsWith(".tsv"));
				if (sources == null) {
					throw new NoSuchFileException("No inputs found in " + inputsDirectory);
				}

				MutableString tsvLine = new MutableString();
				Iterator<MutableString> tsvLines = Utils.readTSVs(sources, tsvLine, null);

				while (tsvLines.hasNext()) {
					long addressId = Utils.hashCode(Utils.column(tsvLine, RECIPIENT));
					long transactionId = Utils.hashCode(Utils.column(tsvLine, SPENDING_TRANSACTION_HASH));

					database.add(INPUT, Utils.longToBytes(transactionId), Utils.longToBytes(addressId));
					this.progress.lightUpdate();

					tsvLines.next();
				}
			}

			this.progress.stop();
			this.progress.start("Building output transactions database");

			{
				LineFilter filter = (line) -> Utils.equalsAtColumn(line, "0", IS_FROM_COINBASE);
				File[] sources = outputsDirectory.toFile().listFiles((d, s) -> s.endsWith(".tsv"));
				if (sources == null) {
					throw new NoSuchFileException("No outputs found in " + outputsDirectory);
				}

				MutableString tsvLine = new MutableString();
				Iterator<MutableString> tsvLines = Utils.readTSVs(sources, tsvLine, filter);

				while (tsvLines.hasNext()) {
					long addressId = Utils.hashCode(Utils.column(tsvLine, RECIPIENT));
					long transactionId = Utils.hashCode(Utils.column(tsvLine, TRANSACTION_HASH));

					database.add(OUTPUT, Utils.longToBytes(transactionId), Utils.longToBytes(addressId));
					this.progress.lightUpdate();

					tsvLines.next();
				}
			}

			this.progress.done();
		}
	}

	public static void main (String[] args) throws IOException, RocksDBException {
		new TransactionsDatabase().compute();
	}
}
