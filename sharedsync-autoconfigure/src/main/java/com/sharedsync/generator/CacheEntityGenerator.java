package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;

import com.sharedsync.generator.Generator.CacheInformation;

public class CacheEntityGenerator{
    private static final String OBJECT_NAME = "cache";

    public static void initialize(CacheInformation cacheInfo) {
        cacheInfo.setBasicPackagePath("sharedsync");
        cacheInfo.setCacheClassName(Generator.capitalizeFirst(cacheInfo.getEntityName()) + Generator.capitalizeFirst(OBJECT_NAME));
        cacheInfo.setCachePath(cacheInfo.getBasicPackagePath() + "." + OBJECT_NAME);
    }

    public static boolean process(CacheInformation cacheInfo, ProcessingEnvironment processingEnv) {

        String source = "package " + cacheInfo.getCachePath() + ";\n"
            + "import org.springframework.stereotype.Component;\n"
            + "import com.sharedsync.shared.repository.AutoCacheRepository;\n"
            + "import " + cacheInfo.getEntityPath() + ";\n"
            + "import " + cacheInfo.getDtoPath() + "." + cacheInfo.getDtoClassName() + ";\n"
            + "@Component\n"
            + "public class " + cacheInfo.getCacheClassName() + " extends AutoCacheRepository<" + cacheInfo.getEntityName() + ", " + cacheInfo.getIdType() + ", " + cacheInfo.getDtoClassName() + "> {}";

        JavaFileObject file;
        try {
            file = processingEnv.getFiler().createSourceFile(cacheInfo.getCachePath() + "." + cacheInfo.getCacheClassName());
            Writer writer = file.openWriter();
            writer.write(source);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        return true;
    }

    

}
