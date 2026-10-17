package tally.proof.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public final class CoverageReport {

  private CoverageReport() {}

  static Map<String, Object> mutation(Path mutationsXml) {
    NodeList mutations = document(mutationsXml).getElementsByTagName("mutation");
    int killed = 0;
    int survived = 0;
    int noCoverage = 0;
    int total = 0;
    for (int index = 0; index < mutations.getLength(); index++) {
      Element mutation = (Element) mutations.item(index);
      if (!mutation.getAttribute("mutatedClass").startsWith("tally.ledger.core.")) {
        continue;
      }
      String status = mutation.getAttribute("status");
      if (status.equals("NON_VIABLE")) {
        continue;
      }
      total++;
      if (status.equals("KILLED")
          || status.equals("TIMED_OUT")
          || status.equals("MEMORY_ERROR")
          || status.equals("RUN_ERROR")) {
        killed++;
      } else if (status.equals("NO_COVERAGE")) {
        noCoverage++;
      } else {
        survived++;
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("mutants", total);
    result.put("killed", killed);
    result.put("survived", survived);
    result.put("no_coverage", noCoverage);
    result.put("score", total == 0 ? 0.0 : Math.round(killed * 10_000.0 / total) / 10_000.0);
    return Map.of("mutation", result);
  }

  static Map<String, Object> tests(Path root) {
    Map<String, Object> byModule = new LinkedHashMap<>();
    int count = 0;
    try (var files = Files.walk(root)) {
      for (Path file :
          files
              .filter(
                  path ->
                      hasTestResultsPath(root, path)
                          && path.getFileName().toString().startsWith("TEST-")
                          && path.getFileName().toString().endsWith(".xml"))
              .toList()) {
        Element suite = document(file).getDocumentElement();
        int tests = Integer.parseInt(suite.getAttribute("tests"));
        int skipped = Integer.parseInt(suite.getAttribute("skipped"));
        tests -= skipped;
        String module = module(root, file);
        byModule.merge(module, tests, (first, second) -> (Integer) first + (Integer) second);
        count += tests;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read test results", e);
    }
    return Map.of("tests", Map.of("count", count, "by_module", byModule));
  }

  static List<Map<String, Object>> coverage(Path root) {
    List<Map<String, Object>> packages = new ArrayList<>();
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.endsWith("jacocoTestReport.xml")).toList()) {
        String module = module(root, file);
        NodeList packageNodes = document(file).getElementsByTagName("package");
        for (int index = 0; index < packageNodes.getLength(); index++) {
          Element packageElement = (Element) packageNodes.item(index);
          NodeList children = packageElement.getChildNodes();
          for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
            Node child = children.item(childIndex);
            if (child instanceof Element counter
                && counter.getTagName().equals("counter")
                && counter.getAttribute("type").equals("LINE")) {
              long missed = Long.parseLong(counter.getAttribute("missed"));
              long covered = Long.parseLong(counter.getAttribute("covered"));
              Map<String, Object> value = new LinkedHashMap<>();
              value.put("module", module);
              value.put("package", packageElement.getAttribute("name").replace('/', '.'));
              value.put(
                  "line_coverage",
                  covered + missed == 0
                      ? 1.0
                      : Math.round(covered * 10_000.0 / (covered + missed)) / 10_000.0);
              packages.add(value);
            }
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read coverage reports", e);
    }
    return List.copyOf(packages);
  }

  private static Document document(Path path) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(path.toFile());
    } catch (ParserConfigurationException | SAXException e) {
      throw new IllegalStateException("failed to parse report: " + path, e);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read report: " + path, e);
    }
  }

  private static boolean hasTestResultsPath(Path root, Path path) {
    Path relative = root.relativize(path);
    for (int index = 0; index + 3 < relative.getNameCount(); index++) {
      if (relative.getName(index + 1).toString().equals("build")
          && relative.getName(index + 2).toString().equals("test-results")
          && (relative.getName(index + 3).toString().equals("test")
              || relative.getName(index + 3).toString().equals("integrationTest"))) {
        return true;
      }
    }
    return false;
  }

  private static String module(Path root, Path file) {
    Path relative = root.relativize(file);
    return relative.getNameCount() < 2 ? "root" : relative.getName(0).toString();
  }
}
