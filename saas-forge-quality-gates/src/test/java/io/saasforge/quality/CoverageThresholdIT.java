package io.saasforge.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class CoverageThresholdIT {

    private static final Map<String, String> CRITICAL_MODULES = Map.of(
            "iam-service", "iam-service",
            "tenant-access-service", "tenant-access-service",
            "entitlement-service", "entitlement-service",
            "saas-forge-sdk-auth", "saas-forge-sdk-auth",
            "saas-forge-sdk-tenant", "saas-forge-sdk-tenant",
            "saas-forge-sdk-permission", "saas-forge-sdk-permission",
            "saas-forge-sdk-quota", "saas-forge-sdk-quota");

    @Test
    void aggregateAndCriticalModuleCoverageMeetThresholds() throws Exception {
        Path reportPath = Path.of(System.getProperty("coverageReport"));
        assertTrue(Files.isRegularFile(reportPath), "缺少 JaCoCo 聚合覆盖率报告: " + reportPath);

        Document report = parseXml(reportPath);
        assertCoverage(report.getDocumentElement(), "LINE", threshold("lineMinimum"), "全仓行覆盖率");
        assertCoverage(report.getDocumentElement(), "BRANCH", threshold("branchMinimum"), "全仓分支覆盖率");

        double criticalMinimum = threshold("criticalLineMinimum");
        NodeList groups = report.getDocumentElement().getElementsByTagName("group");
        Set<String> reportedCriticalModules = new HashSet<>();
        for (int index = 0; index < groups.getLength(); index++) {
            Element group = (Element) groups.item(index);
            String reportName = group.getAttribute("name");
            String module = CRITICAL_MODULES.get(reportName);
            if (module != null) {
                reportedCriticalModules.add(reportName);
                assertCoverage(group, "LINE", criticalMinimum, module + " 行覆盖率");
            }
        }
        assertEquals(CRITICAL_MODULES.keySet(), reportedCriticalModules, "JaCoCo 聚合报告缺少关键模块");
    }

    private static void assertCoverage(Element parent, String type, double minimum, String label) {
        Element counter = directCounter(parent, type);
        if (counter == null) {
            return;
        }
        long missed = Long.parseLong(counter.getAttribute("missed"));
        long covered = Long.parseLong(counter.getAttribute("covered"));
        long total = missed + covered;
        if (total == 0) {
            return;
        }
        double ratio = (double) covered / total;
        assertTrue(ratio >= minimum,
                () -> "%s %.2f%% 低于门禁 %.2f%%".formatted(label, ratio * 100, minimum * 100));
    }

    private static Element directCounter(Element parent, String type) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element
                    && element.getTagName().equals("counter")
                    && element.getAttribute("type").equals(type)) {
                return element;
            }
        }
        return null;
    }

    private static double threshold(String property) {
        return Double.parseDouble(System.getProperty(property));
    }

    private static Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile());
    }
}
