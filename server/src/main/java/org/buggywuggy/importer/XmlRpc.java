/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Writes XML-RPC calls and reads their answers, for {@link ReadOnlyHttp#rpc}. Values become
 * String, Integer, Boolean, Double, Instant (dateTime.iso8601, read as UTC), byte[], List and
 * Map. Documents with a DTD are refused, so an answer cannot make the parser read files.
 */
public final class XmlRpc {

    /** A fault the server answered with, e.g. an unknown method or missing permission. */
    public static final class Fault extends RuntimeException {
        private final int code;

        Fault(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HH:mm:ss");

    private XmlRpc() {
    }

    static String call(String method, List<?> params) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<methodCall><methodName>")
                .append(escape(method)).append("</methodName><params>");
        for (Object param : params) {
            xml.append("<param>");
            value(xml, param);
            xml.append("</param>");
        }
        return xml.append("</params></methodCall>").toString();
    }

    private static void value(StringBuilder xml, Object value) {
        xml.append("<value>");
        switch (value) {
            case null -> xml.append("<nil/>");
            case String s -> xml.append("<string>").append(escape(s)).append("</string>");
            case Integer i -> xml.append("<int>").append(i).append("</int>");
            case Long l -> xml.append("<int>").append(l).append("</int>");
            case Boolean b -> xml.append("<boolean>").append(b ? 1 : 0).append("</boolean>");
            case List<?> list -> {
                xml.append("<array><data>");
                list.forEach(item -> value(xml, item));
                xml.append("</data></array>");
            }
            default -> throw new IllegalArgumentException("Unsupported XML-RPC parameter: " + value.getClass());
        }
        xml.append("</value>");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The result of a methodResponse; a fault becomes {@link Fault}. */
    static Object result(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new ByteArrayInputStream(body));
        Element root = document.getDocumentElement();
        if (!"methodResponse".equals(root.getTagName())) {
            throw new IllegalArgumentException("not an XML-RPC response");
        }
        Element fault = child(root, "fault");
        if (fault != null) {
            Object value = read(child(fault, "value"));
            Map<?, ?> map = value instanceof Map<?, ?> m ? m : Map.of();
            Object code = map.get("faultCode");
            throw new Fault(code instanceof Integer i ? i : 0, String.valueOf(map.get("faultString")));
        }
        Element params = child(root, "params");
        Element param = params == null ? null : child(params, "param");
        if (param == null) {
            throw new IllegalArgumentException("XML-RPC response without result");
        }
        return read(child(param, "value"));
    }

    private static Object read(Element value) {
        if (value == null) {
            return null;
        }
        Element typed = firstElement(value);
        if (typed == null) {
            return value.getTextContent(); // no type: a string
        }
        String text = typed.getTextContent();
        return switch (typed.getTagName()) {
            case "string" -> text;
            case "int", "i4", "i8" -> Integer.valueOf(text.strip());
            case "boolean" -> "1".equals(text.strip()) || "true".equalsIgnoreCase(text.strip());
            case "double" -> Double.valueOf(text.strip());
            case "nil" -> null;
            case "base64" -> Base64.getMimeDecoder().decode(text.strip());
            case "dateTime.iso8601" -> instant(text.strip());
            case "array" -> {
                List<Object> list = new ArrayList<>();
                Element data = child(typed, "data");
                if (data != null) {
                    for (Element v : children(data, "value")) {
                        list.add(read(v));
                    }
                }
                yield list;
            }
            case "struct" -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Element member : children(typed, "member")) {
                    Element name = child(member, "name");
                    if (name != null) {
                        map.put(name.getTextContent(), read(child(member, "value")));
                    }
                }
                yield map;
            }
            default -> text;
        };
    }

    /** 20260105T09:00:00 (UTC, as Trac sends it), also with dashes or an offset. */
    static Instant instant(String text) {
        try {
            return LocalDateTime.parse(text, COMPACT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException again) {
                try {
                    return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException last) {
                    return null;
                }
            }
        }
    }

    private static Element firstElement(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                list.add(e);
            }
        }
        return list;
    }

    static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
