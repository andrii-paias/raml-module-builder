package org.folio.rest.tools;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import java.util.LinkedList;

public class AnnotationGrabber {
  public static final String  DELIMITER              = "&!!&";
  public static final String  PATH_MAPPING_FILE      = "API_PATH_MAPPINGS";
  public static final String  PATH_ANNOTATION        = Path.class.getName();
  public static final String  CLASS_NAME             = "class";
  public static final String  INTERFACE_NAME         = "interface";
  public static final String  FUNCTION_NAME          = "function";
  public static final String  CLASS_URL              = "url2class";
  public static final String  METHOD_URL             = "url2method";
  public static final String  REGEX_URL              = "regex2method";
  public static final String  URL_PATH_DELIMITER     = "/";
  public static final String  HTTP_METHOD            = "method";
  public static final String  PRODUCES               = "javax.ws.rs.Produces";
  public static final String  CONSUMES               = "javax.ws.rs.Consumes";
  public static final String  METHOD_PARAMS          = "params";
  public static final String  REPLACEMENT_FROM_REGEX = "/\\{.*?\\}/?";
  public static final String  REPLACEMENT_TO_REGEX   = "\\/([^\\/]+)\\/";
  public static final String  PATH_PARAM             = "@javax.ws.rs.PathParam";
  public static final String  HEADER_PARAM           = "@javax.ws.rs.HeaderParam";
  public static final String  QUERY_PARAM            = "@javax.ws.rs.QueryParam";
  public static final String  DEFAULT_PARAM          = "@javax.ws.rs.DefaultValue";
  public static final String  NON_ANNOTATED_PARAM    = "NON_ANNOTATED";
  public static final String  CONTENT_TYPE           = "Content-Type";

  private static final String IMPL_PACKAGE           = "org.folio.rest.impl";

  private static final Logger log = LoggerFactory.getLogger(AnnotationGrabber.class);

  private static boolean generateClient = false;
  private static boolean generateModDescrptor = false;

  private static Map<String, Set<String>> mdUrl2Verbs = null;

  // ^http.*?//.*?/apis/patrons/.*?/fines/.*
  // ^http.*?\/\/.*?\/apis\/patrons\/?(.+?)*
  // ^http.*?\/\/.*?\/apis\/([^\/]+)\/([^\/]+)(\?.*)

  public static JsonObject generateMappings() throws Exception {

    /* this class is one of the drivers for the client generation
     * check if the plugin set the system property in the pom and only if
     * so generate */
    String clientGen = System.getProperty("client.generate");
    String modDescr = System.getProperty("modDescrptor.generate");

    if(clientGen != null){
      generateClient = true;
    }
    if("true".equals(modDescr)){
      generateModDescrptor = true;
    }
    JsonObject globalClassMapping = new JsonObject();

    // get classes in generated package
    ClassPath classPath = ClassPath.from(Thread.currentThread().getContextClassLoader());
    List<ClassPath.ClassInfo> bClasses = new LinkedList<>();
    ImmutableSet<ClassPath.ClassInfo> aClasses = classPath.getAllClasses();
    aClasses.forEach(val -> {
      if (val.getPackageName().equals(RTFConsts.INTERFACE_PACKAGE)) {
        bClasses.add(val);
      }
    });
    // loop over all the classes from the package
    bClasses.forEach(val -> {
      try {
        ClientGenerator cGen = new ClientGenerator();

        // ----------------- class level annotations -----------------------//
        // -----------------------------------------------------------------//

        // will contain all mappings for a specific class in the package
        JsonObject classSpecificMapping = new JsonObject();
        // get annotations via reflection for a class
        Annotation[] annotations = Class.forName(val.toString()).getAnnotations();
        // create an entry for the class name = ex. "class":"com.sling.rest.jaxrs.resource.BibResource"
        classSpecificMapping.put(CLASS_NAME, val.toString());
        classSpecificMapping.put(INTERFACE_NAME, val.toString());

        // loop over all the annotations for the class in order to add the
        // needed info - these are class level annotation - not method level
        for (int i = 0; i < annotations.length; i++) {
          // get the annotation type - example in jersey would we javax.ws.rs.Path
          Class<? extends Annotation> type = annotations[i].annotationType();
          //System.out.println("Values of " + type.getName());
          // get the value of this specific annotation - for example /bibs is a
          // possible value for javax.ws.rs.Path - get value by invoking
          // function
          for (Method method : type.getDeclaredMethods()) {
            Object value = method.invoke(annotations[i], (Object[]) null);
            if (type.isAssignableFrom(Path.class)) {
              classSpecificMapping.put(CLASS_URL, "^" + value);
              if(generateClient){
                cGen.generateClassMeta(val.toString(), value);
              }
              if(generateModDescrptor && classSpecificMapping.getString(CLASS_URL) != null){
                String url = classSpecificMapping.getString(CLASS_URL).substring(2);
                if(!url.contains("rmbtests")){
                  MDGenerator.ProvidesEntry pe = MDGenerator.INSTANCE.new ProvidesEntry();
                  if(url.contains("_/tenant")){
                    url = "_tenant";
                  }
                  pe.setId(url);
                  MDGenerator.INSTANCE.addProvidesEntry(pe);
                }
              }
            }
          }
        }

        // ----------------- method level annotations ------------ //
        // ------------------------------------------------------- //

        /** will be used only if ModuleDescriptor generation is turned on
         * maps all http verbs to a single url */
        mdUrl2Verbs = new HashMap<>();

        JsonArray methodsInAPath;
        // iterate over all functions in the class
        Method[] inputMethods = Class.forName(val.toString()).getMethods();
        // sort generated methods to allow comparing generated file with previous versions
        Arrays.sort(inputMethods, Comparator.comparing(Method::toGenericString));
        for (Method inputMethod : inputMethods) {
          JsonObject methodObj = new JsonObject();

          JsonObject params = getParameterNames(inputMethod);

          // get annotations on the method and add all info per method to its
          // own methodObj
          Annotation[] methodAn = inputMethod.getAnnotations();
          // put the name of the function
          methodObj.put(FUNCTION_NAME, inputMethod.getName());
          methodObj.put(METHOD_PARAMS, params);
          for (int j = 0; j < methodAn.length; j++) {

            Class<? extends Annotation> type = methodAn[j].annotationType();
            //System.out.println("Values of " + type.getName());
            if (RTFConsts.POSSIBLE_HTTP_METHOD.contains(type.getName())) {
              // put the method - get or post, etc..
              methodObj.put(HTTP_METHOD, type.getName());
            }
            boolean replaceAccept = false;
            if (type.isAssignableFrom(Produces.class)) {
              //this is the accept header, right now can not send */*
              //so if accept header equals any/ - change this to */*
              replaceAccept = true;
            }
            for (Method method : type.getDeclaredMethods()) {
              Object value = method.invoke(methodAn[j], (Object[]) null);
              if (value.getClass().isArray()) {
                List<Object> retList = new ArrayList<>();
                for (int k = 0; k < Array.getLength(value); k++) {
                  if(replaceAccept){
                    //replace any/any with */* to allow declaring accpet */* which causes compilation issues
                    //when declared in raml. so declare any/any in raml instead and replaced here
                    retList.add(((String)Array.get(value, k)).replaceAll("any/any", ""));
                  }
                  else{
                    retList.add(Array.get(value, k));
                  }
                }
                // put generically things like consumes, produces as arrays
                // since they can have multi values
                methodObj.put(type.getName(), retList);
              } else {
                if (type.isAssignableFrom(Path.class)) {
                  String path = classSpecificMapping.getString(CLASS_URL) + value;
                  String regexPath = getRegexForPath(path);
                  // put path to function
                  methodObj.put(METHOD_URL, path);
                  // put regex path to function
                  methodObj.put(REGEX_URL, regexPath);
                }
                //System.out.println(" " + method.getName() + ": " + value.toString());
              }
            }
          }
          if(generateClient){
            cGen.generateMethodMeta(methodObj.getString(FUNCTION_NAME),
              methodObj.getJsonObject(METHOD_PARAMS),
              methodObj.getString(METHOD_URL),
              methodObj.getString(HTTP_METHOD),
              methodObj.getJsonArray(CONSUMES),
              methodObj.getJsonArray(PRODUCES));
          }
          // if there was no @Path annotation - use the one declared on the
          // class
          if (methodObj.getString(METHOD_URL) == null) {
            methodObj.put(METHOD_URL, classSpecificMapping.getString(CLASS_URL));
            methodObj.put(REGEX_URL, getRegexForPath(classSpecificMapping.getString(CLASS_URL)));
          }


          if(generateModDescrptor){
            String verb = methodObj.getString(HTTP_METHOD);
            verb = verb.substring(verb.lastIndexOf(".")+1);
            String rootURL4Service = classSpecificMapping.getString(CLASS_URL).substring(1);
            /*if(mdUrl2Verbs.get(path.substring(1)) != null){
              mdUrl2Verbs.get(path.substring(1)).add(verb);
            } else {
              mdUrl2Verbs.put(path.substring(1), new JsonArray());
              mdUrl2Verbs.get(path.substring(1)).add(verb);
            }*/
            if(mdUrl2Verbs.get(rootURL4Service) != null){
              mdUrl2Verbs.get(rootURL4Service).add(verb);
            } else {
              mdUrl2Verbs.put(rootURL4Service, new HashSet<String>());
              mdUrl2Verbs.get(rootURL4Service).add(verb);
            }
          }

          // this is the key - the regex path is the key to the functions
          // represented by this url
          // an array of functions which answer to this url (with get, delete,
          // post, etc... methods)
          methodsInAPath = classSpecificMapping.getJsonArray(methodObj.getString(REGEX_URL));
          if (methodsInAPath == null) {
            methodsInAPath = new JsonArray();
            classSpecificMapping.put(methodObj.getString(REGEX_URL), methodsInAPath);
          }
          methodsInAPath.add(methodObj);

        }
        // System.out.println( val.toString() );
        globalClassMapping.put(classSpecificMapping.getString(CLASS_URL), classSpecificMapping);

        if(generateClient){
          cGen.generateClass(classSpecificMapping);
        }
        if(generateModDescrptor){
          BiConsumer<String, Set<String>> biConsumer = (key, value) -> {
            if(!key.contains("_/tenant") && !key.contains("rmbtests")){
              MDGenerator.RoutingEntry re = MDGenerator.INSTANCE.new RoutingEntry();
              JsonArray ja = new JsonArray();
              value.forEach( verb -> {
                ja.add(verb);
              });
              re.setMethods(ja);
              re.setEntryPath(key);
              re.setLevel("30");
              re.setType("request-response");
              MDGenerator.INSTANCE.addRoutingEntry( re );
            }
          };
          mdUrl2Verbs.forEach( biConsumer );
          MDGenerator.INSTANCE.generateMD();
          //this is needed when the MDGenerator is used to generate
          //partial MDs in submodules. the system variable is maintained
          //across the sub module builds and if not reset will generate a
          //partial MD for all sub modules
          System.setProperty("modDescrptor.generate", "false");

        }

      } catch (Exception e) {
        log.error(e.getMessage(), e);
      }
    });
    //writeMappings(globalClassMapping);
    return globalClassMapping;
  }

  public static JsonObject getParameterNames(Method method) throws Exception {
    // need to handle default values
    JsonObject retObject = new JsonObject();

    Parameter[] nonAnnotationParams = method.getParameters();
    Annotation[][] annotations = method.getParameterAnnotations();
    Class[] parameterTypes = method.getParameterTypes();
    int k = 0;
    for (Annotation[] annotation : annotations) {
      Class parameterType = parameterTypes[k++];
      if (annotation.length == 0) {
        // we are here because - there is a param but it is not annotated - this
        // will occur for post / put
        // requests - the entity to save/update will not be annotated
        JsonObject obj = null;
        obj = new JsonObject();
        obj.put("value", nonAnnotationParams[k - 1].getName()); // this will be
                                                                // a generic
                                                                // name - unless
                                                                // debug info
                                                                // turned on for
                                                                // javac (store
                                                                // information
                                                                // about method
                                                                // parameters)
        obj.put("type", parameterType.getCanonicalName());
        obj.put("order", k - 1);
        obj.put("param_type", NON_ANNOTATED_PARAM);
        retObject.put("" + (k - 1), obj);
      }
      JsonObject prevObjForDefaultVal = null;
      for (Annotation a : annotation) {
        JsonObject obj = null;
        if (a instanceof HeaderParam) {
          obj = new JsonObject();
          obj.put("value", ((HeaderParam) a).value());
          obj.put("type", parameterType.getCanonicalName());
          obj.put("order", k - 1);
          obj.put("param_type", HEADER_PARAM);
        } else if (a instanceof PathParam) {
          obj = new JsonObject();
          obj.put("value", ((PathParam) a).value());
          obj.put("type", parameterType.getCanonicalName());
          obj.put("order", k - 1);
          obj.put("param_type", PATH_PARAM);
        } else if (a instanceof QueryParam) {
          obj = new JsonObject();
          obj.put("value", ((QueryParam) a).value());
          obj.put("type", parameterType.getCanonicalName());
          obj.put("order", k - 1);
          obj.put("param_type", QUERY_PARAM);
        } else if (a instanceof DefaultValue && prevObjForDefaultVal != null) {
          // default values originate in the raml and appear after the parameter
          // they are to be applied to
          String defaultValue = ((DefaultValue) a).value();
          // push it into the previously scanned parameter
          prevObjForDefaultVal.put("default_value", defaultValue);
          prevObjForDefaultVal = null;
        }
        if (obj != null) {
          prevObjForDefaultVal = obj;
          // obj may be null in case of @DefaultValue annotation which i am
          // currently ignoring
          retObject.put("" + (k - 1), obj);
        }
      }
    }
    return retObject;
  }

  private static String getRegexForPath(String path) {
    // fix this hack - by writing a better regex
    String regexPath = path.replaceAll(REPLACEMENT_FROM_REGEX, REPLACEMENT_TO_REGEX);
    // regexPath = regexPath.replaceAll(REPLACEMENT_FROM_REGEX,
    // REPLACEMENT_TO_REGEX);

    if (regexPath.endsWith(URL_PATH_DELIMITER)) {
      regexPath = regexPath.concat("?");
    } else {
      regexPath = regexPath.concat("/?");
    }
    regexPath = regexPath.concat("$");
    return regexPath;
  }



}
