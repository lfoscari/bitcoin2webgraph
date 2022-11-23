package it.unimi.dsi.law;

import com.opencsv.*;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import it.unimi.dsi.fastutil.objects.Object2LongFunction;
import it.unimi.dsi.io.ByteBufferInputStream;
import it.unimi.dsi.logging.ProgressLogger;
import it.unimi.dsi.util.BloomFilter;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static it.unimi.dsi.law.Parameters.*;
import static it.unimi.dsi.law.Parameters.BitcoinColumn.*;

public class DownloadInputsOutputs {
	private final ProgressLogger progress;
	private final Object2LongFunction<String> addressLong;
	private long count = 0;

	public DownloadInputsOutputs () {
		this(new ProgressLogger(LoggerFactory.getLogger(DownloadInputsOutputs.class)));
	}

	public DownloadInputsOutputs (ProgressLogger progress) {
		this.progress = progress;
		this.addressLong = new Object2LongArrayMap<>();
	}

	public void run () throws IOException {
		this.download(Parameters.inputsUrlsFilename.toFile(), INPUTS_AMOUNT, false);
		this.download(Parameters.outputsUrlsFilename.toFile(), OUTPUTS_AMOUNT, true);
		this.saveAddressMap();
	}

	private void download (File urls, int limit, boolean computeBloomFilters) throws IOException {
		Parameters.inputsDirectory.toFile().mkdir();
		Parameters.outputsDirectory.toFile().mkdir();

		if (computeBloomFilters) {
			Parameters.filtersDirectory.toFile().mkdir();
		}

		try (FileReader reader = new FileReader(urls)) {
			List<String> toDownload = new BufferedReader(reader).lines().toList();

			if (limit >= 0) {
				this.progress.start("Downloading and unpacking first " + INPUTS_AMOUNT + " url in " + urls + "...");
				toDownload = toDownload.subList(0, limit);
			} else {
				this.progress.start("Downloading and unpacking all urls in " + urls + "...");
			}

			for (String s : toDownload) {
				URL url = new URL(s);
				String filename = s.substring(s.lastIndexOf("/") + 1, s.indexOf(".gz?"));

				try (GZIPInputStream gzip = new GZIPInputStream(url.openStream())) {
					List<String[]> tsv = Utils.readTSV(gzip, false);
					boolean contentful = this.parseTSV(tsv, filename, computeBloomFilters);

					if (contentful) {
						this.progress.lightUpdate();
					}
				}
			}

			if (computeBloomFilters) {
				this.progress.stop("Bloom filters saved in " + Parameters.filtersDirectory);
			}
		}

		this.progress.stop();
	}

	public boolean parseTSV (List<String[]> tsv, String filename, boolean computeBloomFilters) throws IOException {
		List<Integer> important;
		Path destinationPath;

		if (filename.contains("input")) {
			important = INPUTS_IMPORTANT;
			destinationPath = Parameters.inputsDirectory.resolve(filename);
		} else {
			important = OUTPUTS_IMPORTANT;
			destinationPath = Parameters.outputsDirectory.resolve(filename);
		}

		List<String[]> filtered = tsv
				.stream()
				.filter(line -> !line[IS_FROM_COINBASE].equals("1"))
				.map(line -> important.stream().map(i -> line[i]).toList().toArray(String[]::new))
				.toList();

		if (filtered.size() <= 1) {
			return false;
		}

		this.saveTSV(filtered, destinationPath);
		this.saveAddresses(filtered);

		if (computeBloomFilters) {
			this.saveBloomFilter(destinationPath);
		}

		return true;
	}

	private void saveTSV (List<String[]> filtered, Path destinationPath) throws IOException {
		try (FileWriter destinationWriter = new FileWriter(destinationPath.toString());
			 CSVWriter tsvWriter = new CSVWriter(destinationWriter, '\t', '"', '\\', "\n")) {
			tsvWriter.writeAll(filtered, false);
		}
	}

	private void saveAddresses (List<String[]> filtered) {
		for (String[] line : filtered) {
			String address = line[INPUTS_IMPORTANT.indexOf(RECIPIENT)];
			this.addressLong.put(address, this.count++);
		}
	}

	private void saveAddressMap () throws IOException {
		this.progress.start("Saving address map...");
		BinIO.storeObject(this.addressLong, Parameters.addressLongMap.toFile());
		this.progress.stop("Address map saved in " + Parameters.addressLongMap);
	}

	private void saveBloomFilter (Path outputPath) throws IOException {
		BloomFilter<CharSequence> transactionFilter = BloomFilter.create(1000, BloomFilter.STRING_FUNNEL);
		Utils.readTSV(outputPath.toFile(), true).forEach(line -> transactionFilter.add(line[OUTPUTS_IMPORTANT.indexOf(TRANSACTION_HASH)].getBytes()));
		BinIO.storeObject(transactionFilter, Parameters.filtersDirectory.resolve(outputPath.getFileName()).toFile());
	}

	public static void main (String[] args) throws IOException {
		new DownloadInputsOutputs().run();
	}
}
