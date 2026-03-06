package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;

import com.sharedsync.generator.Generator.CacheInformation;
import com.sharedsync.generator.Generator.FieldInfo;

/**
 * Generates a factory class with a static create(...) method that instantiates
 * the entity with all fields set via reflection. Works even if the entity has
 * no constructors (uses Unsafe as fallback).
 */
public class EntityAllArgsConstructorGenerator {

    public static void initialize(CacheInformation cacheInfo) {
        // No additional initialization needed for this generator
    }

    private static final String FACTORY_PACKAGE = "sharedsync.allArgsConstructor";

    public static boolean process(CacheInformation cacheInfo, ProcessingEnvironment processingEnv) {
        String className = cacheInfo.getEntityName();
        String factoryClassName = className + "AllArgsConstructor";
        String qualifiedFactory = FACTORY_PACKAGE + "." + factoryClassName;

        try {
            JavaFileObject jfo = processingEnv.getFiler().createSourceFile(qualifiedFactory);
            try (Writer w = jfo.openWriter()) {
                w.write("package " + FACTORY_PACKAGE + ";\n\n");
                
                // Import the entity class
                if (cacheInfo.getEntityPath() != null) {
                    w.write("import " + cacheInfo.getEntityPath() + ";\n\n");
                }

                w.write("/**\n");
                w.write(" * Auto-generated factory for " + className + ".\n");
                w.write(" * Provides a static create(...) method to instantiate the entity with all fields.\n");
                w.write(" * Uses cached reflection for optimal performance.\n");
                w.write(" */\n");
                w.write("public class " + factoryClassName + " {\n\n");

                // Static cached fields for reflection (initialized once)
                w.write("    // Cached reflection objects (initialized once, thread-safe via class loading)\n");
                w.write("    private static final java.lang.reflect.Constructor<" + className + "> CONSTRUCTOR;\n");
                w.write("    private static final boolean USE_UNSAFE;\n");
                w.write("    private static final sun.misc.Unsafe UNSAFE;\n");
                
                for (FieldInfo f : cacheInfo.getEntityFields()) {
                    String n = f.getName();
                    w.write("    private static final java.lang.reflect.Field FIELD_" + n.toUpperCase() + ";\n");
                }
                
                w.write("\n");
                w.write("    static {\n");
                w.write("        try {\n");
                w.write("            // Try to get no-arg constructor\n");
                w.write("            java.lang.reflect.Constructor<" + className + "> ctor = null;\n");
                w.write("            boolean useUnsafe = false;\n");
                w.write("            sun.misc.Unsafe unsafe = null;\n");
                w.write("            try {\n");
                w.write("                ctor = " + className + ".class.getDeclaredConstructor();\n");
                w.write("                ctor.setAccessible(true);\n");
                w.write("            } catch (NoSuchMethodException e) {\n");
                w.write("                // No no-arg constructor: prepare Unsafe\n");
                w.write("                useUnsafe = true;\n");
                w.write("                java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField(\"theUnsafe\");\n");
                w.write("                unsafeField.setAccessible(true);\n");
                w.write("                unsafe = (sun.misc.Unsafe) unsafeField.get(null);\n");
                w.write("            }\n");
                w.write("            CONSTRUCTOR = ctor;\n");
                w.write("            USE_UNSAFE = useUnsafe;\n");
                w.write("            UNSAFE = unsafe;\n");
                w.write("\n");
                w.write("            // Cache all field references\n");
                
                for (FieldInfo f : cacheInfo.getEntityFields()) {
                    String n = f.getName();
                    w.write("            FIELD_" + n.toUpperCase() + " = " + className + ".class.getDeclaredField(\"" + n + "\");\n");
                    w.write("            FIELD_" + n.toUpperCase() + ".setAccessible(true);\n");
                }
                
                w.write("        } catch (Exception e) {\n");
                w.write("            throw new ExceptionInInitializerError(e);\n");
                w.write("        }\n");
                w.write("    }\n\n");

                // generate create method signature
                w.write("    public static " + className + " create(");
                StringBuilder params = new StringBuilder();
                for (int i = 0; i < cacheInfo.getEntityFields().size(); i++) {
                    FieldInfo f = cacheInfo.getEntityFields().get(i);
                    String t;
                    if (f.isIgnored()) {
                        t = Generator.denormalizeType(f.getType(), f.getOriginalType());
                    } else if (f.isOneToMany() || f.isManyToMany()) {
                        // use List<FullType> for collection relations
                        t = "java.util.List<" + f.getType() + ">";
                    } else {
                        t = Generator.denormalizeType(f.getType(), f.getOriginalType());
                    }
                    String n = f.getName();
                    params.append(t).append(" ").append(n);
                    if (i < cacheInfo.getEntityFields().size() - 1) params.append(", ");
                }
                w.write(params.toString());
                w.write(") {\n");

                // instantiate using cached constructor/unsafe
                w.write("        try {\n");
                w.write("            " + className + " instance = USE_UNSAFE\n");
                w.write("                ? (" + className + ") UNSAFE.allocateInstance(" + className + ".class)\n");
                w.write("                : CONSTRUCTOR.newInstance();\n");
                w.write("\n");
                w.write("            // Set fields using cached Field references\n");

                for (FieldInfo f : cacheInfo.getEntityFields()) {
                    String n = f.getName();
                    w.write("            FIELD_" + n.toUpperCase() + ".set(instance, " + n + ");\n");
                }

                w.write("\n");
                w.write("            return instance;\n");
                w.write("        } catch (Exception e) {\n");
                w.write("            throw new RuntimeException(\"Failed to create " + className + " instance\", e);\n");
                w.write("        }\n");

                w.write("    }\n\n");

                w.write("}\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    

    

}
