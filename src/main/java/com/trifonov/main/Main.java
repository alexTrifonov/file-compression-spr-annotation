package com.trifonov.main;

import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;


import com.trifonov.compression.RelocatingCompressor;
public class Main {
	
	private final static Logger logger = LogManager.getLogger();
	private final static String THREAD = "thread";
	
	public static void main(String[] args) {
		logger.info("APP IS STARTED.");
		GenericApplicationContext ctx = new AnnotationConfigApplicationContext(CompressionConfig.class);
		RelocatingCompressor compressor = ctx.getBean("compressor", RelocatingCompressor.class);
		
		Arrays.stream(args).forEach(arg -> {
			String[] argElements = arg.split("=");			
			if (THREAD.equalsIgnoreCase(argElements[0])) {
				try {
					int threadCount = Integer.parseInt(argElements[1]);
					if (threadCount > 0 && threadCount < 11) compressor.setThreadCount(threadCount);								
				} catch (NumberFormatException e) {
					logger.info("You incorrect set up thread count. Thread count has default value = {}", compressor.getThreadCount());			
				}
			} 
			
		});
		
		
		logger.info("thread count = {}", compressor.getThreadCount());		
		compressor.compress();
		
		
		ctx.close();
	}

}
