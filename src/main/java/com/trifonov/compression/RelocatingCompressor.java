package com.trifonov.compression;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tinify.AccountException;
import com.tinify.ClientException;
import com.tinify.ConnectionException;
import com.tinify.ServerException;
import com.tinify.Source;
import com.tinify.Tinify;

import lombok.Data;

/**
 * Класс для сжатия файлов.
 * Сжатые файлы после сжатия помещает в заданную директорию. Исходные файлы удаляет.
 * @author Alexandr Trifonov
 *
 */
@Data
@Service("compressor")
public class RelocatingCompressor implements Compressor {

	/**
	 * Количество потоков для сжатия файлов.
	 */
	@Value("${thread.count}")
	private int threadCount;
	
	private final Logger logger = LogManager.getLogger();
	
	/**
	 * Имя файла со списком ключей.
	 */
	@Value("${keys.file}")
	private String keysFileName;
	
	/**
	 * Имя директории, в которую перемещаются сжатые файлы
	 */
	@Value("${result.dir}")
	private String resultDirName;
	
	/**
	 * Директория, в которой запускается jar файл приложения.
	 */
	private Path workPath = Paths.get("").toAbsolutePath();
	
	/**
	 * Path директории, в которую перемещаются сжатые файлы 
	 */
	private Path resultPath;
	
	/**
	 * Объект для создания отчетов в виде текстовых файлов.
	 */
	private FileReportCreator reportCreator;
	
	/**
	 * Количество сжатых файлов.
	 */
	private AtomicInteger countCompressed;
	
	/**
	 * Очередь с файлами для сжатия.
	 */
	private Queue<FileInfo> sourceFiles;
	
	/**
	 * Очередь с файлами, которые не удалось сжать, либо возникли ошибки при перемещении в папку со сжатыми файлами, 
	 * либо возникли ошибки при удалении файлов из папки с исходными файлами после сжатия
	 */
	private Queue<FileInfo> failedFiles;
	
	/**
	 * Список ключей.
	 */
	private List<String> keys;
	
	/**
	 * Очередь с битыми ключами.
	 */
	private Queue<String> failedKeys;	
	
	/**
	 * Map с количеством сжатых файлов на каждом ключе.
	 */
	private Map<String, Integer> filesByKey;
	
	/**
	 * Класс для получения списка файлов для сжатия.
	 */
	private ImageFileVisitor imageFileVisitor;
	
	/**
	 * Имя файла с отчетом о количестве сжатых файлов в результате работы приложения. 
	 */
	@Value("${count.compressing.file}")
	private String countCompressingFile;
	
	/**
	 * Имя файла для отчета со списком  файлов, которые не удалось сжать, либо возникли ошибки при перемещении в папку со сжатыми файлами, 
	 * либо возникли ошибки при удалении файлов из папки с исходными файлами после сжатия.
	 */
	@Value("${fail.files.file}")
	private String failedFilesFile;
	
	/**
	 * Имя файла для отчета со списком не зарегистрированных на сервере Tinify ключей (не принадлежащих аутентифицированным пользователям).
	 */
	@Value("${fail.keys.file}")
	private String failedKeysFile;
	
	/**
	 * Имя файла для отчета со списком ключей и количеством сжатых файлов по каждому ключу.
	 */
	@Value("${files.by.key.file}")
	private String filesByKeyFile;
	
	@PostConstruct
	private void init() {
		countCompressed = new AtomicInteger(0);
		sourceFiles = new ConcurrentLinkedQueue<>(imageFileVisitor.getSourceFiles());
		failedFiles = new ConcurrentLinkedQueue<>();		
		failedKeys = new ConcurrentLinkedQueue<>();
		filesByKey = new ConcurrentHashMap<>();
		
		keys = getKeys(keysFileName);
		logger.info("keys: {}", keys);
		keys.forEach(e -> filesByKey.put(e, 0));
		
		
		
		resultPath = workPath.resolve(Paths.get(resultDirName));
		Path sourcePathAbs = workPath.resolve(Paths.get(imageFileVisitor.getSourceDir()));
		Iterator<Path> directoryIterator = imageFileVisitor.getDirectories().iterator();
		
		while(directoryIterator.hasNext()) {
			Path currentSourcePath = directoryIterator.next();
			Path path = resultPath.resolve(sourcePathAbs.relativize(currentSourcePath));
			try {
				Files.createDirectories(path);
			} catch (IOException e) {				
				logger.error("Fail Creating result directories. IOException ", e);
				System.exit(1);				
			}
		}
	}
	
	
	/**
	 * Метод для сжатия ключей. Сжатие осуществляется с использованием библиотеки Tinify.
	 * Для сжатия используются ключи, полученные пользователем на сайте Tinify.
	 * После успешного сжатия каждый сжатый файл записывается в директорию resultDirName в точно такую же поддиректорию (или поддиректории),
	 * как в исходной директории для сжимаемых файлов. Исходная директория для сжимаемых файлов задается в объекте ImageFileVisitor.
	 */
	@Override
	public void compress() {
				
		Path sourcePath = workPath.resolve(Paths.get(imageFileVisitor.getSourceDir()));
		ExecutorService pool;
		
		logger.info("COMPRESSION START. TOTAL COUNT FILES FOR COMPRESSION = {}", sourceFiles.size());
		// Каждый ключ используется для последовательного сжатия файлов. Ключ используется до получения исключения, 
		// сообщающего об исчерпании лимита на количество сжимаемых файлов.
		// После получения исключения ключ не используется. Из списка ключей извлекается следующий ключ
		// и процесс сжатия продолжается пока не закончатся действующие ключи либо сжимаемые файлы.
		pool = Executors.newFixedThreadPool(threadCount);
		Iterator<String> keyIterator = keys.iterator();
		while (keyIterator.hasNext()) {
			String key = keyIterator.next();
			pool.submit(() -> {
				Tinify.setKey(key);
				boolean validKey = true;
				//первоначальная проверка валидности ключа				
				try {
					validKey = Tinify.validate();
				} catch (Exception e) {
					validKey = false;
					failedKeys.add(key);
					logger.error("Exception Tinify.validate, key = {}, message = {}", key, e.getMessage(), e);
				}				
				while (validKey && !sourceFiles.isEmpty()) {
					FileInfo fileInfo = sourceFiles.poll();
					try {
						Path relativePath = sourcePath.relativize(fileInfo.getPath());
						Path currentResultFile = resultPath.resolve(relativePath);
						Source source = Tinify.fromFile(fileInfo.getPath().toString());
						source.toFile(currentResultFile.toString());
						Files.delete(fileInfo.getPath());
						countCompressed.incrementAndGet();
						filesByKey.computeIfPresent(key, (keyMap, valueMap) -> ++valueMap);
						logger.info("Compressed file = {}, key = {}", fileInfo.getPath().toString(), key);
					} catch (AccountException e) {
						validKey = false;
						failedKeys.add(key);						
						logger.error("AccountException, message = {}, key = {}, file = {}", e.getMessage(), key,fileInfo.getPath(), e);
					} catch (IOException e) {
						failedFiles.add(fileInfo);
						logger.error("IOException, message = {}, key = {}, file = {}", e.getMessage(), key,	fileInfo.getPath(), e);
					} catch (ClientException e) {
						failedFiles.add(fileInfo);
						logger.error("ClientException, message = {}, key = {}, file = {}", e.getMessage(), key,	fileInfo.getPath(), e);
					} catch (ServerException e) {
						logger.error("ServerException, message = {}, key = {}, file = {}", e.getMessage(), key,	fileInfo.getPath(), e);
					} catch (ConnectionException e) {
						logger.error("ConnectionException, message = {}, key = {}, file = {}", e.getMessage(), key,	fileInfo.getPath(), e);
					}	
				}
			});

		}
		pool.shutdown();
		while (!pool.isTerminated()) {}
			
		if (sourceFiles.isEmpty()) {
			logger.info("SOURCE FILES ARE OVER.");
		} 
	}		
	
	
	
	/**
	 * Метод для получения списка ключей из указанного файла с ключами.
	 * @param keysPath Файл с ключами.
	 * @return
	 */
	private List<String> getKeys(String keysPathName) {		
		keys = new LinkedList<>();
		try {			
			Path currentPathAbs = Paths.get("").toAbsolutePath();
			Path keysPath = currentPathAbs.resolve(keysFileName);
			keys = new LinkedList<>(Files.readAllLines(keysPath, Charset.forName("UTF-8")));
			keys.removeIf(str -> str.isEmpty());			
		} catch (IOException e) {
			logger.error("Failed read keys list. IOException. ", e);
			logger.info("EPIC FAIL");
			System.exit(1);
		}
		
		if (keys.isEmpty()) {
			logger.info("File with keys is empty.");
			logger.info("EPIC FAIL");
			System.exit(1);
		}
		return keys;
	}
	
	//destroy-method. создает отчеты по сжатым файлам и использованным ключам в виде текстовых файлов
	@PreDestroy
	private void compressionReport() {				
		String lineSeparator = System.lineSeparator();		
		
		reportCreator.writeReport(countCompressed.toString(), countCompressingFile);

		if (!failedFiles.isEmpty()) {
			String reportFiles =   failedFiles.stream().map(FileInfo::getPath).map(x -> x + lineSeparator).reduce("", String::concat).trim();				
			reportCreator.writeReport(reportFiles, failedFilesFile);
		} 
		
		if (!failedKeys.isEmpty()) {
			String reportKeys =   failedKeys.stream().map(x -> x + lineSeparator).reduce("", String::concat).trim();
			reportCreator.writeReport(reportKeys, failedKeysFile);
		}
		
		String reportFilesOnKey = filesByKey.entrySet().stream().map(entry -> entry.getKey() + " - " + entry.getValue() + lineSeparator).reduce("", String::concat).trim();
		reportCreator.writeReport(reportFilesOnKey, filesByKeyFile);
		logger.info("COMPRESSION IS FINISHED. App compressed {} files", countCompressed);
	}

	
	
	public FileReportCreator getReportCreator() {
		return reportCreator;
	}

	@Autowired
	public void setReportCreator(FileReportCreator reportCreator) {
		this.reportCreator = reportCreator;
	}


	public ImageFileVisitor getImageFileVisitor() {
		return imageFileVisitor;
	}

	@Autowired
	public void setImageFileVisitor(ImageFileVisitor imageFileVisitor) {
		this.imageFileVisitor = imageFileVisitor;
	}
	
	
}
