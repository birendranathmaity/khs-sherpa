package com.khs.example.endpoint;


import org.junit.Test;

import com.khs.sherpa.servlet.EndpointScanner;
//endpoint scanner test
public class EndpointScanTest {

	@Test
	public void test() {
				
		EndpointScanner scanner = new EndpointScanner();
		scanner.classPathScan("com.khs.example.endpoints");
		
	}
	

}


