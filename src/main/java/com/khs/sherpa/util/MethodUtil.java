package com.khs.sherpa.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.khs.sherpa.annotation.Action;

public class MethodUtil {

	public static List<Method> getAllMethods(Class<?> clazz) {

		List<Method> methods = new ArrayList<Method>();
		boolean hasGenericInterface = clazz.getGenericInterfaces().length > 0;
		
		System.out.println(hasGenericInterface);
		
		for(Method method: clazz.getDeclaredMethods()) {
			method.getGenericParameterTypes();
			method.getGenericReturnType();

			method.isBridge();
			// skip all method assignable from Object class
			if(method.getDeclaringClass().isAssignableFrom(Object.class)) {
				continue;
			} else if(method.isAnnotationPresent(Action.class) && MethodUtil.getActionAnnotation(method).disabled()) {
				continue;
			} else if(method.isBridge()) {
				continue;
			}
			methods.add(method);
		}
		return methods;
	}
	
	public static String getMethodName(Method method) {
		Action action = MethodUtil.getActionAnnotation(method);
		if(action != null) {
			if(action.value().trim().length() > 0) {
				return action.value().trim();
			}
		}
		
		return method.getName();
	}
	
	public static Method getMethodByName(List<Method> methods, String theMethodName) {
		for(Method method: methods) {
			if(theMethodName.equals(MethodUtil.getMethodName(method))) {
				return method;
			}
		}
		return null;
	}
	
	public static Method getMethodByName(Class<?> clazz, String theMethodName) {
		return MethodUtil.getMethodByName(MethodUtil.getAllMethods(clazz), theMethodName);
	}
	
	public static Action getActionAnnotation(Method method) {
		if(method.isAnnotationPresent(Action.class)) {
			return method.getAnnotation(Action.class);
		}	
		return null;
	}
}
