package io.github.larkbatis.maven;

import java.io.StringReader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;

final class TestXml {

    private TestXml() {
    }

    static Xpp3Dom dom(String xml) {
        try {
            return Xpp3DomBuilder.build(new StringReader(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException(xml, e);
        }
    }
}
