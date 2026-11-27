package io.github.jxt159753.blockspaceprogram;

import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PackageDependenciesTest {
    private static final String PACKAGE_PREFIX = "io.github.jxt159753.blockspaceprogram.";
    private static final String MOD_CLASS = PACKAGE_PREFIX + "BlockSpaceProgram";
    private static final List<String> LAYERS = List.of("part", "collision", "assembly", "orbit", "sky", "client");

    @Test
    public void importsFollowLayerOrder() throws IOException {
        Path sourceDirectory = Path.of(System.getProperty("blockspaceprogram.mainJavaSources"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var violations = new ArrayList<String>();
        try (var files = Files.walk(sourceDirectory);
                var fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var javaFiles = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            var task = (JavacTask) compiler.getTask(null, fileManager, null, List.of(), null,
                    fileManager.getJavaFileObjectsFromPaths(javaFiles));
            for (var source : task.parse()) {
                if (source.getPackageName() == null) {
                    continue;
                }
                int sourceLayer = LAYERS.indexOf(layerOf(source.getPackageName().toString()));
                if (sourceLayer < 0) {
                    continue;
                }
                Path path = sourceDirectory.relativize(Path.of(source.getSourceFile().toUri()));
                boolean networking = path.getFileName().toString().endsWith("Networking.java");
                for (var declaration : source.getImports()) {
                    String target = declaration.getQualifiedIdentifier().toString();
                    if (!target.startsWith(PACKAGE_PREFIX) || target.equals(MOD_CLASS)
                            || target.startsWith(MOD_CLASS + ".")) {
                        continue;
                    }
                    String layer = layerOf(target);
                    int targetLayer = LAYERS.indexOf(layer);
                    if ((targetLayer < 0 || targetLayer > sourceLayer) && !(networking && layer.equals("client"))) {
                        violations.add(path + " imports " + target);
                    }
                }
            }
        }
        assertTrue("Invalid package dependencies:\n" + String.join("\n", violations), violations.isEmpty());
    }

    private static String layerOf(String qualifiedName) {
        return qualifiedName.startsWith(PACKAGE_PREFIX)
                ? qualifiedName.substring(PACKAGE_PREFIX.length()).split("\\.", 2)[0]
                : "";
    }
}
