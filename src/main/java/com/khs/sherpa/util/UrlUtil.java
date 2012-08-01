package com.khs.sherpa.util;

/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

import com.khs.sherpa.servlet.ReflectionCache;

public class UrlUtil {

	public static String getPath(HttpServletRequest request) {
		String url = request.getRequestURI();
		
		url = StringUtils.removeStart(url, request.getContextPath());
		url = StringUtils.removeStart(url, request.getServletPath());
		
		return url;
	}
	
	public static String getRequestParameter(HttpServletRequest request, String param) {
		return request.getParameter(param);
	}
	
	public static String getRequestUrlParameter(HttpServletRequest request, String path, String param) {
		String currentUrl = UrlUtil.getPath(request);
		Pattern pattern = Pattern.compile("(\\{\\w+\\})");
		Matcher matcher = pattern.matcher(path);
		int cnt = 0;
		while(matcher.find()) {
			if(matcher.group().equals("{"+param+"}")) {
				String matcherText = matcher.replaceAll("([^/]*)");
				pattern = Pattern.compile(matcherText);
				matcher = pattern.matcher(currentUrl);
				matcher.find();
				return matcher.group(cnt+1);
			}
			cnt++;
		}
		return null;
	}
	
	// not easily tested
	public static String getRequestBody(HttpServletRequest request) {
		if(request.getMethod().equals("GET") || request.getMethod().equals("DELETE")) {
			return null;
		}
		
		StringBuilder stringBuilder = new StringBuilder();
		BufferedReader bufferedReader = null;
		try {
			InputStream inputStream = request.getInputStream();
			if (inputStream != null) {
				bufferedReader = new BufferedReader(new InputStreamReader(
						inputStream));
				char[] charBuffer = new char[128];
				int bytesRead = -1;
				while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
					stringBuilder.append(charBuffer, 0, bytesRead);
				}
			} else {
				stringBuilder.append("");
			}
		} catch (IOException ex) {
			// to nothing
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (IOException ex) {

				}
			}
		}
		return stringBuilder.toString();
	}
	
	public static String getParamValue(HttpServletRequest request, String param) {
		String value = null;
		String currentUrl = UrlUtil.getPath(request);
		String path = ReflectionCache.getUrl(currentUrl, request.getMethod());
		if(StringUtils.isNotEmpty(path)) {
			if(StringUtils.contains(path, ".")) {
				path = StringUtils.removeStart(path, request.getMethod() + ".");
			}
			value = UrlUtil.getRequestUrlParameter(request, path, param);
		}
		
		if(value == null) {
			value = UrlUtil.getRequestParameter(request, param);
		}
		
		return value;
		
	}
	
	protected static String getCacheUrl(String url, String method) {
		String path = ReflectionCache.getUrl(url, method);
		if(path == null) {
			path = ReflectionCache.getUrlMethod(url, method);
		}
		return path;
	}
}
