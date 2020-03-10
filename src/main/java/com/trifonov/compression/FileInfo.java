package com.trifonov.compression;

import java.nio.file.Path;

import lombok.Data;

/**
 * Класс описания характеристик файла.
 * @author Alexandr Trifonov
 *
 */
@Data
public class FileInfo implements Comparable<FileInfo> {
	/**
	 * Абсолютное имя файла
	 */
	private Path path;
	/**
	 * Размер файла в байтах.
	 */
	private long size;
	
	/**
	 * Конструктор для создания FileInfo.
	 */
	public FileInfo() {
		
	}
	
	/**
	 * Конструктор для создания FileInfo с заданными значениями.
	 * @param path Абсолютный Path файла.  
	 * @param size Размер файла в байтах.
	 */
	public FileInfo(Path path, long size) {
		this.path = path;
		this.size = size;
	}
	
	/*
	 * Для упорядочения от файла с большим размером к файлу с меньшим размером.
	 */
	@Override
	public int compareTo(FileInfo anotherFile) {
		int compareSize = Long.compare(anotherFile.size, this.size);
		
		if (compareSize != 0) {
			return compareSize;
		} else {
			return this.path.compareTo(anotherFile.path);
		}		
	}
	
	
}
