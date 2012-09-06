package com.khs.sherpa.parser;

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


import org.apache.commons.lang3.StringEscapeUtils;

import com.khs.sherpa.annotation.Encode;
import com.khs.sherpa.annotation.Param;
import com.khs.sherpa.context.ApplicationContext;
import com.khs.sherpa.context.ApplicationContextAware;

public class StringParamParser implements ApplicationContextAware, ParamParser<String> {
	
	public static final String DEFAULT = "com.khs.sherpa.DEFUALT_STRING_FORMAT";
	
	private ApplicationContext applicationContext;
	
	public String parse(String value, Param annotation, Class<?> clazz) {
		String format = annotation.format();
		
		if(format == null || format.equals("")) {
			format = (String) applicationContext.getAttribute(DEFAULT);
		}
		
		return this.applyEncoding(value, format);
	}

	public boolean isValid(Class<?> clazz) {
		return clazz.isAssignableFrom(String.class);
	}

	private String applyEncoding(String value,String format) {
		String result = value;
		if (format != null) {
			if (format.equals(Encode.XML)) {
				result = StringEscapeUtils.escapeXml(value);		
			} else if (format.equals(Encode.HTML)) {
				result = StringEscapeUtils.escapeHtml4(value);		
			} else if (format.equals(Encode.CSV)) {
				result = StringEscapeUtils.escapeCsv(value);
			}
		}
		  
		return result;
	}

	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}
}
