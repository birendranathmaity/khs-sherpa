package com.khs.sherpa.servlet;

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

import static com.khs.sherpa.util.Util.msg;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.logging.Logger;

import javax.annotation.security.DenyAll;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;

import com.khs.sherpa.annotation.Action;
import com.khs.sherpa.annotation.ContentType;
import com.khs.sherpa.annotation.Endpoint;
import com.khs.sherpa.annotation.Param;
import com.khs.sherpa.exception.SherpaActionNotFoundException;
import com.khs.sherpa.exception.SherpaPermissionExcpetion;
import com.khs.sherpa.exception.SherpaRuntimeException;
import com.khs.sherpa.json.service.AuthenticationException;
import com.khs.sherpa.json.service.JSONService;
import com.khs.sherpa.json.service.SessionStatus;
import com.khs.sherpa.json.service.SessionToken;
import com.khs.sherpa.util.Constants;
import com.khs.sherpa.util.MethodUtil;
import com.khs.sherpa.util.SettingsContext;
import com.khs.sherpa.util.UrlUtil;

class SherpaRequest {

	private static Logger LOG = Logger.getLogger(SherpaRequest.class.getName());
	
	private String endpoint;
	private String action;
	
	private Object target;
	private Object responseData;
	
	private HttpServletRequest servletRequest;
	private HttpServletResponse servletResponse;
	
	private SessionStatus sessionStatus = null;
	private JSONService service;
	
	private String userid;
	private String token;
	
	public String getEndpoint() {
		return endpoint;
	}
	
	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}
	
	public String getAction() {
		return action;
	}
	
	public void setAction(String action) {
		this.action = action;
	}

	public Object getResponseData() {
		return responseData;
	}

	public void setTarget(Object target) {
		this.target = target;
	}

	public void setServletRequest(HttpServletRequest servletRequest) {
		this.servletRequest = servletRequest;
	}

	public void setSessionStatus(SessionStatus sessionStatus) {
		this.sessionStatus = sessionStatus;
	}

	public void setService(JSONService service) {
		this.service = service;
	}

	public HttpServletResponse getServletResponse() {
		return servletResponse;
	}

	public void setServletResponse(HttpServletResponse servletResponse) {
		this.servletResponse = servletResponse;
	}

	public void loadRequest(HttpServletRequest request, HttpServletResponse response) {
		
		String urlMethod = ReflectionCache.getUrlMethod(UrlUtil.getPath(request), request.getMethod());
		if(StringUtils.isNotEmpty(urlMethod)) {
			String[] str = StringUtils.split(urlMethod, ".");
			this.setEndpoint(str[0]);
			this.setAction(str[1]);
		} else {
			this.setEndpoint(request.getParameter("endpoint"));
			this.setAction(request.getParameter("action"));
		}
		
		this.setServletRequest(request);
		this.setServletResponse(response);
	}
	
	private boolean isAuthRequest(HttpServletRequest request) {
		String endpoint = this.getEndpoint();
		if(endpoint != null && endpoint.equals("null")) {
			endpoint =  null;
		}
		
		String action = this.getAction();
		
		if(endpoint == null && action.equals(Constants.AUTHENTICATE_ACTION)) {
			return true;
		}
		return false;
		
	}
	
	private void validateMethod(Method method) throws SherpaRuntimeException {
		if(target.getClass().getAnnotation(Endpoint.class).authenticated()) {
			if(!sessionStatus.equals(SessionStatus.AUTHENTICATED)) {
				throw new SherpaPermissionExcpetion("token is invalid or has expired");
			} else if(method.isAnnotationPresent(DenyAll.class)) {
				throw new SherpaPermissionExcpetion("method ["+method.getName()+"] in class ["+target.getClass().getCanonicalName()+"] has `@DenyAll` annotation" );
			} else if(method.isAnnotationPresent(RolesAllowed.class)) {
				for(String role: method.getAnnotation(RolesAllowed.class).value()) {
					if(service.getTokenService().hasRole(userid, token, role)) {
						return ;
					}
				}
				throw new SherpaPermissionExcpetion("method ["+method.getName()+"] in class ["+target.getClass().getCanonicalName()+"] has `@RolesAllowed` annotation" );
			}
		}
	}
	
	private Object invokeMethod(Method method) {
		try {
			return method.invoke(target, this.getParams(method));
		} catch (Exception e) {
			e.printStackTrace();
			throw new SherpaActionNotFoundException("unable to execute method ["+method.getName()+"] in class ["+target.getClass().getCanonicalName()+"]");
		}
	}
	
	private Object[] getParams(Method method) {
		RequestMapper map = new RequestMapper();
		map.setService(service);
		map.setRequest(servletRequest);
		map.setResponse(servletResponse);
		
		Class<?>[] types = method.getParameterTypes();
		Object[] params = null;
		// get parameters
		if(types.length > 0) {
			params = new Object[types.length];
		}
		
		Annotation[][] parameters = method.getParameterAnnotations();
		for(int i=0; i<parameters.length; i++) {
			Class<?> type = types[i];
			Annotation annotation = null;
			if(parameters[i].length > 0 ) {
				for(Annotation an: parameters[i]) {
					if(an.annotationType().isAssignableFrom(Param.class)) {
						annotation = an;
						break;
					}
				}
				
			}
			params[i] = map.map(method.getClass().getName(),method.getName(),type, annotation);	
		}
		return params;
	}
	
	private Method findMethod(String name) {
		Method method = MethodUtil.getMethodByName(target.getClass(), name);
		if(method == null) {
			throw new SherpaActionNotFoundException("no method found ["+name+"] in class ["+target.getClass().getCanonicalName()+"]");
		}
		return method;
	}
	
	static <T> T[] append(T[] arr, T element) {
	    final int N = arr.length;
	    arr = Arrays.copyOf(arr, N + 1);
	    arr[N] = element;
	    return arr;
	}
	
	public void run() {
		
		// see if there's a json call back function specified
		String callback = this.servletRequest.getParameter("callback");
		if(isAuthRequest(servletRequest)) {
			String userid = servletRequest.getParameter("userid");
			String password = servletRequest.getParameter("password");
			try {
				SessionToken token = this.service.authenticate(userid, password);
				this.service.getTokenService().activate(userid, token);
				
				// load the sherpa admin user
				if(this.service.getTokenService().hasRole(userid, token.getToken(), SettingsContext.getSettings().sherpaAdmin)) {
					String[] roles = token.getRoles();
					token.setRoles(append(roles, "SHERPA_ADMIN"));
				}
				
				log(msg("authenticated"), userid, "*****");
				if (SettingsContext.getSettings().jsonpSupport && callback != null) {
					servletResponse.setContentType("text/javascript");
				    this.service.mapJsonp(this.getResponseOutputStream(),token,callback);	
				} else {
				  this.service.map(this.getResponseOutputStream(), token);
				}    
			} catch (AuthenticationException e) {
				this.service.error("Authentication Error Invalid Credentials", this.getResponseOutputStream());
				log(msg("invalid authentication"), userid, "*****");
			}
			
			return;
		} else {
			sessionStatus = this.service.validToken(getToken(), getUserId());
			
		}
		
		if(target == null) {
			throw new SherpaRuntimeException("@Endpoint not found initialized");
		}
		
		Method method = this.findMethod(action);
		ContentType type = ContentType.JSON;
		if(method.isAnnotationPresent(Action.class)) {
			type = method.getAnnotation(Action.class).contentType();
		}
		
		servletResponse.setContentType(type.type);
		try {
			this.validateMethod(method);
			if (SettingsContext.getSettings().jsonpSupport && callback != null) {
				servletResponse.setContentType("text/javascript");
			    this.service.mapJsonp(this.getResponseOutputStream(), this.invokeMethod(method),callback);	
			} else {
				this.service.map(this.getResponseOutputStream(), this.invokeMethod(method));
			}
		} catch (SherpaRuntimeException e) {
			//this.service.error(e,this.getResponseOutputStream());
			throw e;
		}
		
	}
	
	private String getUserId() {
		String userid = servletRequest.getHeader("userid");
		if(userid == null) {
			userid = servletRequest.getParameter("userid");
		}
		this.userid = userid;
		return userid;
	}
	
	private String getToken() {
		String token = servletRequest.getHeader("token");
		if(token == null) {
			token = servletRequest.getParameter("token");
		}
		this.token = token;
		return token;
	}
	
	private void log(String action, String email, String token) {
		LOG.info(msg(String.format("Executed - %s,%s,%s ", action, email, token)));
	}
	
	private OutputStream getResponseOutputStream() {
		try {
			return servletResponse.getOutputStream();
		} catch (IOException e) {
			return null;
		}
	}
}
