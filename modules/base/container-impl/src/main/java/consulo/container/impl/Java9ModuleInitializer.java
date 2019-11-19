/*
 * Copyright 2013-2019 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.container.impl;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author VISTALL
 * @since 2019-11-19
 */
public class Java9ModuleInitializer {
  private static final Method java_io_File_toPath = findMethod(File.class, "toPath");

  private static final Class java_nio_file_Path = findClass("java.nio.file.Path");
  private static final Class java_lang_Module = findClass("java.lang.Module");
  private static final Class java_lang_module_ModuleFinder = findClass("java.lang.module.ModuleFinder");
  private static final Class java_lang_module_Configuration = findClass("java.lang.module.Configuration");
  private static final Class java_lang_ModuleLayer = findClass("java.lang.ModuleLayer");
  private static final Class java_lang_ModuleLayer$Controller = findClass("java.lang.ModuleLayer$Controller");
  private static final Class java_util_function_Function = findClass("java.util.function.Function");
  private static final Class java_util_Optional = findClass("java.util.Optional");

  private static final Method java_lang_ModuleLayer_boot = findMethod(java_lang_ModuleLayer, "boot");
  private static final Method java_lang_ModuleLayer_findModule = findMethod(java_lang_ModuleLayer, "findModule", String.class);
  private static final Method java_lang_ModuleLayer_configuration = findMethod(java_lang_ModuleLayer, "configuration");
  private static final Method java_lang_ModuleLayer_defineModules = findMethod(java_lang_ModuleLayer, "defineModules", java_lang_module_Configuration, List.class, java_util_function_Function);
  private static final Method java_lang_ModuleLayer$Controller_layout = findMethod(java_lang_ModuleLayer$Controller, "layer");

  private static final Method java_lang_Module_addOpens = findMethod(java_lang_Module, "addOpens", String.class, java_lang_Module);

  private static final Method java_util_Optional_get = findMethod(java_util_Optional, "get");
  private static final Method java_lang_module_ModuleFinder_of = findMethod(java_lang_module_ModuleFinder, "of", java_nio_file_Path.arrayType());
  private static final Method java_lang_module_Configuration_resolve =
          findMethod(java_lang_module_Configuration, "resolve", java_lang_module_ModuleFinder, List.class, java_lang_module_ModuleFinder, Collection.class);

  public static void initializeBaseModules(List<File> files, final ClassLoader targetClassLoader) {
    Object emptyPathArray = Array.newInstance(java_nio_file_Path, 0);

    Object paths = Array.newInstance(java_nio_file_Path, files.size());
    for (int i = 0; i < files.size(); i++) {
      File file = files.get(i);

      Array.set(paths, i, instanceInvoke(java_io_File_toPath, file));
    }

    Object moduleFinder = staticInvoke(java_lang_module_ModuleFinder_of, paths);

    List<String> toResolve = new ArrayList<String>();

    toResolve.add("consulo.desktop.awt.hacking");

    Object bootModuleLayer = staticInvoke(java_lang_ModuleLayer_boot);

    Object confBootModuleLayer = instanceInvoke(java_lang_ModuleLayer_configuration, bootModuleLayer);

    Object configuration =
            staticInvoke(java_lang_module_Configuration_resolve, moduleFinder, Collections.singletonList(confBootModuleLayer), staticInvoke(java_lang_module_ModuleFinder_of, emptyPathArray),
                         toResolve);

    Object functionLambda = Proxy.newProxyInstance(Java9ModuleInitializer.class.getClassLoader(), new Class[]{java_util_function_Function}, new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("apply".equals(method.getName())) {
          return targetClassLoader;
        }
        throw new UnsupportedOperationException(method.getName());
      }
    });

    Object controller = staticInvoke(java_lang_ModuleLayer_defineModules, configuration, Collections.singletonList(bootModuleLayer), functionLambda);

    alohomora(bootModuleLayer, controller);
  }

  private static void alohomora(Object bootModuleLayer, Object controller) {
    Object javaDesktopModule = findModuleUnwrap(bootModuleLayer, "java.desktop");

    Object plaformModuleLayer = instanceInvoke(java_lang_ModuleLayer$Controller_layout, controller);

    Object desktopHackingModule = findModuleUnwrap(plaformModuleLayer, "consulo.desktop.awt.hacking");

    instanceInvoke(java_lang_Module_addOpens, javaDesktopModule, "sun.awt", desktopHackingModule);
    instanceInvoke(java_lang_Module_addOpens, javaDesktopModule, "sun.java2d", desktopHackingModule);
    instanceInvoke(java_lang_Module_addOpens, javaDesktopModule, "java.awt", desktopHackingModule);
    instanceInvoke(java_lang_Module_addOpens, javaDesktopModule, "javax.swing", desktopHackingModule);
  }

  private static <T> T findModuleUnwrap(Object moduleLayer, String moduleName) {
    Object optionalValue = instanceInvoke(java_lang_ModuleLayer_findModule, moduleLayer, moduleName);

    return instanceInvoke(java_util_Optional_get, optionalValue);
  }

  @SuppressWarnings("unchecked")
  private static <T> T instanceInvoke(Method method, Object instance, Object... args) {
    try {
      return (T)method.invoke(instance, args);
    }
    catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T staticInvoke(Method method, Object... args) {
    try {
      return (T)method.invoke(null, args);
    }
    catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private static Method findMethod(Class<?> cls, String methodName, Class... args) {
    try {
      Method declaredMethod = cls.getDeclaredMethod(methodName, args);
      declaredMethod.setAccessible(true);
      return declaredMethod;
    }
    catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private static Class findClass(String cls) {
    try {
      return Class.forName(cls);
    }
    catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }
}
