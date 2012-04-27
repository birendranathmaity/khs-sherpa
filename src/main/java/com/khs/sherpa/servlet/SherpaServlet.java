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

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.khs.sherpa.annotation.Endpoint;
import com.khs.sherpa.annotation.Param;
import com.khs.sherpa.json.service.AuthenticationException;
import com.khs.sherpa.json.service.DefaultActivityService;
import com.khs.sherpa.json.service.DefaultTokenService;
import com.khs.sherpa.json.service.DefaultUserService;
import com.khs.sherpa.json.service.JSONService;
import com.khs.sherpa.json.service.SessionStatus;
import com.khs.sherpa.json.service.SessionToken;
import com.khs.sherpa.json.service.UserService;
import static com.khs.sherpa.util.Constants.*;
import static com.khs.sherpa.util.Util.*;

/**
 * Servlet implementation class ImageDisplayServlet
 */

@Endpoint
public class SherpaServlet extends HttpServlet {

	private static final String SESSION_TIMED_OUT = "SESSION TIMED OUT";

	Logger LOG = Logger.getLogger(SherpaServlet.class.getName());

	private static final String INVALID_TOKEN = "INVALID AUTHENTICATION TOKEN";

	private static final long serialVersionUID = 4345668988238038540L;
	private static final String AUTHENTICATE_ACTION = "authenticate";
	private Settings settings = new Settings();
	private JSONService service = new JSONService();

	public SherpaServlet() {
		super();
	}

	public void authenticate(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String id = request.getParameter("userid");
		String password = request.getParameter("password");
		try {
			SessionToken token = this.service.authenticate(id, password);
			this.service.getTokenService().activate(id, token);
			log("authenticated", id, "n/a");
			this.service.map(response.getOutputStream(), token);

		} catch (AuthenticationException e) {
			this.service.error(e, response.getOutputStream());
			log(msg("invalid authentication"), id, "n/a");
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		doGet(request, response);

	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

		String action = request.getParameter("action");
		String endpoint = request.getParameter("endpoint");

		if (this.settings.endpointPackage == null) {
			this.service.error("endpointPackage not specified, configure as SherpaServlet init() parameter...", response.getOutputStream());
		}

		if (action == null) {
			this.service.error("action parameter not specified", response.getOutputStream());
		}

		if (endpoint == null && !action.equals(AUTHENTICATE_ACTION)) {
			this.service.error("endpoint not specified", response.getOutputStream());
		}

		boolean isAuthenticate = action.equals(AUTHENTICATE_ACTION);

		Class clazz = null;
		if (!isAuthenticate) {

			try {
				clazz = Class.forName(this.settings.endpointPackage + endpoint);

			} catch (ClassNotFoundException e) {
				this.service.error("Endpoint " + this.settings.endpointPackage + endpoint + " not found", response.getOutputStream());
			}
		} else {
			clazz = this.getClass();
		}

		// make sure endpoint is a sherpa endpoint

		Endpoint ep = (Endpoint) clazz.getAnnotation(Endpoint.class);
		if (ep == null) {
			this.service.error("Endpoint " + clazz + " is not @SherpaEndpoint", response.getOutputStream());
		}

		if (action == null) {
			this.service.error("action not specified", response.getOutputStream());
		}

		if (isAuthenticate) {

			authenticate(request, response);

		} else {

			// // validate session token, if authenticated endpoint
			SessionStatus status = validToken(request);
			if (ep.authenticated() && status == SessionStatus.NOT_AUTHENTICATED) {
				this.service.error(INVALID_TOKEN, response.getOutputStream());
			} else if (ep.authenticated() && status == SessionStatus.TIMED_OUT) {
				this.service.error(SESSION_TIMED_OUT, response.getOutputStream());
			} else {
				executeEndpoint(request, response, action, clazz);
			}

		}

	}

	private void executeEndpoint(HttpServletRequest request, HttpServletResponse response, String action, Class clazz) throws IOException {
		boolean found = false;
		try {
			Method[] methods = clazz.getMethods();
			for (Method m : methods) {
				if (m.getName().equals(action)) {
					found = true;

					Class[] types = m.getParameterTypes();
					Object[] params = null;
					// get parameters
					if (types.length > 0) {
						Annotation[][] parameters = m.getParameterAnnotations();
						// Annotation[] annotations = parameters[0];
						params = new Object[types.length];
						int i = 0;
						for (Annotation[] annotations : parameters) {
							for (Annotation annotation : annotations) {
								Object result = map(types[i], request, annotation);
								params[i] = result;
								i++;
							}
						}

					}

					try {
						Object target = clazz.newInstance();
						Object result = m.invoke(target, params);
						this.service.map(response.getOutputStream(), result);
						this.service.getActivityService().logActivity("anonymous","executed endpoint:"+target.getClass().getName()+" method:"+m.getName()+" parameters:"+params);
						
					} catch (Throwable e) {
						this.service.error("action " + action + " with  parameter types " + types + " failed", response.getOutputStream());
						throw new RuntimeException(e);
					}
				}
			}
		} catch (Exception e) {
			this.service.error(e, response.getOutputStream());
			throw new RuntimeException(e);

		}
		if (!found) {
			this.service.error("Action " + action + " not found...", response.getOutputStream());
		}
	}

	private Object map(Class type, HttpServletRequest request, Annotation annotation) {

		Param a = null;
		Object result = null;

		if (annotation.annotationType() == Param.class) {
			a = (Param) annotation;
		}

		if (a == null) {
			throw new RuntimeException("endpoint @Param annotation required");
		}

		if (a.value() == null) {
			throw new RuntimeException("parameters required");
		}

		if (type == String.class) {

			String s = new String(request.getParameter(a.value()));
			result = s;
		} else if (type == Integer.class || type == int.class) {
			String s = request.getParameter(a.value());
			try {
				result = Integer.parseInt(s);
			} catch (NumberFormatException e) {
				throw new RuntimeException("endpoint parameter " + a.value() + "= "+s+" must be integer ");
			}
		} else if (type == Float.class || type == float.class) {

			String s = request.getParameter(a.value());
			try {
				result = Float.parseFloat(s);
			} catch (NumberFormatException e) {
				throw new RuntimeException("endpoint parameter " + a.value() + "= "+s+" must be float ");
			}

		} else if (type == Double.class || type == double.class) {

			String s = request.getParameter(a.value());
			try {
				result = Double.parseDouble(s);
			} catch (NumberFormatException e) {
				throw new RuntimeException("endpoint parameter " + a.value() + "= "+s+"  must be double ");
			}
		} else if (type == Date.class) {

			String s = request.getParameter(a.value());
			String fmt = this.settings.dateFormat;
			try {
				if (!a.format().isEmpty()) {
					fmt = a.format();
				}
				DateFormat format = new SimpleDateFormat(fmt);
				result = format.parseObject(s);
			} catch (ParseException e) {
				throw new RuntimeException("endpoint parameter " + a.value() + "= "+s+" invalid date format must be " + fmt);
			}

		} else if (type == Boolean.class || type == boolean.class) {

			String s = request.getParameter(a.value());
			if (!(s.equalsIgnoreCase("1") || s.equalsIgnoreCase("Y") || s.equalsIgnoreCase("0") || s.equalsIgnoreCase("N"))) {
				throw new RuntimeException("endpoint parameter " + a.value() + "= "+s+" invalid boolean format must be Y/N or 0/1");
			}
			if (s.equalsIgnoreCase("Y") || s.equalsIgnoreCase("1")) {
				result = new Boolean(true);
			} else {
				result = new Boolean(false);
			}

		} else if (type == Calendar.class) {

			String s = request.getParameter(a.value());
			String fmt = this.settings.dateFormat;
			try {
				if (!a.format().isEmpty()) {
					fmt = a.format();
				}
				DateFormat format = new SimpleDateFormat(fmt);
				Calendar cal = Calendar.getInstance();
				cal.setTime((Date) format.parseObject(s));
				result = format.parseObject(s);
			} catch (ParseException e) {
				throw new RuntimeException("endpoint parameter " + a.value() + " invalid date format must be " + fmt);
			}

		} else {
			throw new RuntimeException("Type " + type + " not supported...");
		}

		return result;

	}

	private void log(String action, String email, String token) {
		LOG.info(msg(SHERPA_SERVER+String.format("Executed - %s,%s,%s ", action, email, token)));
	}

	protected SessionStatus validToken(HttpServletRequest request) {
		String userid = request.getParameter("userid");
		String token = request.getParameter("token");
		return validToken(token, userid);
	}

	protected SessionStatus validToken(String token, String userid) {
		if (token == null || userid == null) {
			return SessionStatus.NOT_AUTHENTICATED;
		}
		return this.service.validToken(token, userid);
	}

	private void initUserService(String serviceName) {

		try {
			Class clazz = Class.forName(serviceName);
			UserService service = (UserService) clazz.newInstance();
			this.service.setUserService(service);

		} catch (ClassNotFoundException e) {
			error("User service class not found " + serviceName);
			throw new RuntimeException();
		} catch (InstantiationException e) {
			error("User service class could be instantiated " + serviceName);
			throw new RuntimeException();
		} catch (IllegalAccessException e) {
			error("User service class could not be accessed " + serviceName);
			throw new RuntimeException();
		}

	}

	private void error(String message) {
		LOG.log(Level.SEVERE, msg(SHERPA_SERVER_NOT_STARTED + message));

	}

	@Override
	public void init() throws ServletException {

		// read default properties
		Properties properties = null;
		try {
			InputStream in = this.getClass().getClassLoader().getResourceAsStream("sherpa.properties");
		    if (in != null) {
		      properties = new Properties();
			  properties.load(in);
			  LOG.info(msg(SHERPA_SERVER+"sherpa properties loaded"));
			}
		} catch (IOException e) {
         // does'nt exist...
			   error("sherpa properties not found, defaults applied...");
		}
		
		String value = properties.getProperty("endpoint.package");
		
		// Get the value of an initialization parameter
		//String value = getServletConfig().getInitParameter("endpoint-package");
		String userServiceClazzName = properties.getProperty("user.service");
		if (userServiceClazzName == null) {
			this.service.setUserService(new DefaultUserService());
			this.service.setTokenService(new DefaultTokenService());
			this.service.setActivityService(new DefaultActivityService());
		} else {
			initUserService(userServiceClazzName);
		}
		if (value != null) {
			this.settings.endpointPackage = value;
			int end = this.settings.endpointPackage.length();
			// add package seperator
			if (this.settings.endpointPackage.lastIndexOf('.') != end - 1) {
				this.settings.endpointPackage += ".";
			}
		} else {
			
			throw new RuntimeException(SHERPA_SERVER_NOT_STARTED+"endpoint package location must be defined in sherpa.properties");
			
		}
		
		initTimeout(properties);
		initDataTypes(properties);
		

	}
	
	
	private void initTimeout(Properties props) {
		
		String value = props.getProperty("session.timeout");
		if (value != null) {
			try {
				long timeout = Long.parseLong(value);
				this.service.setSessionTimeout(timeout);
				LOG.info(msg(SHERPA_SERVER+"session timeout set to "+timeout+" ms"));
				
			} catch(NumberFormatException e) {
				
				error("ERROR reading session.timeout value from property file, value must be long");
				throw new RuntimeException("ERROR reading session.timeout value from property file, value must be long");
			}
			
		}
		
		LOG.info(msg(SHERPA_SERVER+"session timeout set to "+this.service.getSessionTimeout()+" ms"));
		
		
		
	}
	
	
	private void initDataTypes(Properties props) {
		
		String value = props.getProperty("date.format");
		if (value != null) {
			this.settings.dateFormat = value;		
		}
		
		value = props.getProperty("time.format");
		if (value != null) {
			this.settings.timeFormat = value;		
		}
		
		LOG.info(msg(SHERPA_SERVER+"session timeout set to "+this.service.getSessionTimeout()+" ms"));
		
		
		
	}
	
	
	
	
	
}