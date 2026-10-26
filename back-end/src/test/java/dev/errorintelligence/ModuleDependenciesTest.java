package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ModuleDependenciesTest {
  @Test
  void businessModuleDependenciesRemainAcyclic() throws Exception {
    Path root = Path.of("src/main/java/dev/errorintelligence");
    Map<String, Set<String>> graph = new TreeMap<>();
    var reference = Pattern.compile("dev\\.errorintelligence\\.([a-z]+)\\.");
    try (var sources = Files.walk(root)) {
      for (Path file : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
        if (root.relativize(file).getNameCount() < 2) continue;
        String owner = root.relativize(file).getName(0).toString();
        var dependencies = graph.computeIfAbsent(owner, ignored -> new TreeSet<>());
        var matches = reference.matcher(Files.readString(file));
        while (matches.find())
          if (!owner.equals(matches.group(1))) dependencies.add(matches.group(1));
      }
    }
    for (String module : graph.keySet()) visit(module, graph, new LinkedHashSet<>());
  }

  private void visit(String module, Map<String, Set<String>> graph, Set<String> path) {
    assertThat(path).as("Dependency cycle ending in %s: %s", module, path).doesNotContain(module);
    path.add(module);
    for (String next : graph.getOrDefault(module, Set.of())) visit(next, graph, path);
    path.remove(module);
  }
}
