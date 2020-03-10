package com.trifonov.compression;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Класс для получения списка сжимаемых файлов.
 * @author Alexandr Trifonov
 *
 */
@Data
@Component
public class ImageFileVisitor implements FileVisitor<Path> {
	
	private final Logger logger = LogManager.getLogger();
	
	/**
	 * Set доступных файлов для сжатия.
	 */
	private SortedSet<FileInfo> sourceFiles;

	/**
	 * Список файлов, которые не удалось прочитать при проходе по директории.
	 */
	private List<FileInfo> failedReadFiles;
	
	/**
	 * Множество с поддиректориями, получаемое при проходе по дереву каталогов.
	 */
	private Set<Path> directories;
	
	/**
	 * Директория, в которой расположены сжимаемые файлы и поддиректории с сжимаемыми файлами.
	 */
	@Value("${source.dir}")
	private String sourceDir;
	
	/**
	 * Имя файла для отчета со списком  файлов, которые не удалось прочитать.
	 */
	@Value("${fail.reading.files.file}")
	private String failReadingFile;
	
	/**
	 * Объект для создания отчетов в виде текстовых файлов.
	 */
	private FileReportCreator reportCreator;
	
	public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
		return FileVisitResult.CONTINUE;
	}

	public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
		sourceFiles.add(new FileInfo(file, Files.size(file)));
		directories.add(file.getParent());
		return FileVisitResult.CONTINUE;
	}

	public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
		failedReadFiles.add(new FileInfo(file, 0));
		//failedReadFiles.add(new FileInfo(file.toString(), 0));
		return FileVisitResult.CONTINUE;
	}

	public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
		return FileVisitResult.CONTINUE;
	}

	/**
	 * Метод для наполнения списка json-объектов файлов для сжатия и списка
	 * json-объектов файлов, к которым не удалось получить доступ.
	 * 
	 * @param sourceDir          Директория с файлами либо файл со списком
	 *                            json-объектов.
	 * @param initFilesList       Список json-объектов файлов для сжатия.
	 * @param failedReadFilesList Список json-объектов файлов, к которым не удалось
	 *                            получить доступ.
	 */
	@PostConstruct
	private void fillFilesList() {
		Path currentPathAbs = Paths.get("").toAbsolutePath();
		sourceFiles = new TreeSet<>();
		failedReadFiles = new LinkedList<>();
		directories = new HashSet<>();
		try {
			Path sourcePath = currentPathAbs.resolve(Paths.get(sourceDir));	
			if (!Files.exists(sourcePath)) {
				logger.error("Source directory \"{}\" for target files is not existed", sourceDir);
				logger.info("EPIC FAIL.");
				System.exit(1);
			}
			Files.walkFileTree(sourcePath, this);			
			if (sourceFiles.isEmpty()) {
				logger.info("Source directory \"{}\" for target files is empty. Nothing to compress", sourceDir);
			}
		} catch (IOException e) {
			logger.error("Failed get list all files for compressing. IOException. ", e);			
			System.exit(1);
		} 			
	}
	
	//destroy-method. Создает отчет со списком непрочитанных файлов.
	@PreDestroy
	private void walkReport() {				
		String lineSeparator = System.lineSeparator();
		if (!failedReadFiles.isEmpty()) {
			String reportFiles = failedReadFiles.stream().map(FileInfo::getPath).map(x -> x + lineSeparator).reduce("", String::concat).trim();				
			reportCreator.writeReport(reportFiles, failReadingFile);
		}
	}
	
	public FileReportCreator getReportCreator() {
		return reportCreator;
	}
	
	
	@Autowired
	public void setReportCreator(FileReportCreator reportCreator) {
		this.reportCreator = reportCreator;
	}

	

}
