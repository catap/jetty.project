//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.Utf8Appendable.NotUtf8Exception;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * URI Utility methods.
 * <p>
 * This class assists with the decoding and encoding or HTTP URI's.
 * It differs from the java.net.URL class as it does not provide
 * communications ability, but it does assist with query string
 * formatting.
 * </p>
 *
 * @see UrlEncoded
 */
public class URIUtil
    implements Cloneable
{
    private static final Logger LOG = Log.getLogger(URIUtil.class);
    public static final String SLASH = "/";
    public static final String HTTP = "http";
    public static final String HTTPS = "https";

    // Use UTF-8 as per http://www.w3.org/TR/html40/appendix/notes.html#non-ascii-chars
    public static final Charset __CHARSET = StandardCharsets.UTF_8;

    private URIUtil()
    {
    }

    /**
     * Encode a URI path.
     * This is the same encoding offered by URLEncoder, except that
     * the '/' character is not encoded.
     *
     * @param path The path the encode
     * @return The encoded path
     */
    public static String encodePath(String path)
    {
        if (path == null || path.length() == 0)
            return path;

        StringBuilder buf = encodePath(null, path, 0);
        return buf == null ? path : buf.toString();
    }

    /**
     * Encode a URI path.
     *
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @return The StringBuilder or null if no substitutions required.
     */
    public static StringBuilder encodePath(StringBuilder buf, String path)
    {
        return encodePath(buf, path, 0);
    }

    /**
     * Encode a URI path.
     *
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @return The StringBuilder or null if no substitutions required.
     */
    private static StringBuilder encodePath(StringBuilder buf, String path, int offset)
    {
        byte[] bytes = null;
        if (buf == null)
        {
            loop:
            for (int i = offset; i < path.length(); i++)
            {
                char c = path.charAt(i);
                switch (c)
                {
                    case '%':
                    case '?':
                    case ';':
                    case '#':
                    case '"':
                    case '\'':
                    case '<':
                    case '>':
                    case ' ':
                    case '[':
                    case '\\':
                    case ']':
                    case '^':
                    case '`':
                    case '{':
                    case '|':
                    case '}':
                        buf = new StringBuilder(path.length() * 2);
                        break loop;
                    default:
                        if (c > 127)
                        {
                            bytes = path.getBytes(URIUtil.__CHARSET);
                            buf = new StringBuilder(path.length() * 2);
                            break loop;
                        }
                }
            }
            if (buf == null)
                return null;
        }

        int i;

        loop:
        for (i = offset; i < path.length(); i++)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '%':
                    buf.append("%25");
                    continue;
                case '?':
                    buf.append("%3F");
                    continue;
                case ';':
                    buf.append("%3B");
                    continue;
                case '#':
                    buf.append("%23");
                    continue;
                case '"':
                    buf.append("%22");
                    continue;
                case '\'':
                    buf.append("%27");
                    continue;
                case '<':
                    buf.append("%3C");
                    continue;
                case '>':
                    buf.append("%3E");
                    continue;
                case ' ':
                    buf.append("%20");
                    continue;
                case '[':
                    buf.append("%5B");
                    continue;
                case '\\':
                    buf.append("%5C");
                    continue;
                case ']':
                    buf.append("%5D");
                    continue;
                case '^':
                    buf.append("%5E");
                    continue;
                case '`':
                    buf.append("%60");
                    continue;
                case '{':
                    buf.append("%7B");
                    continue;
                case '|':
                    buf.append("%7C");
                    continue;
                case '}':
                    buf.append("%7D");
                    continue;

                default:
                    if (c > 127)
                    {
                        bytes = path.getBytes(URIUtil.__CHARSET);
                        break loop;
                    }
                    buf.append(c);
            }
        }

        if (bytes != null)
        {
            for (; i < bytes.length; i++)
            {
                byte c = bytes[i];
                switch (c)
                {
                    case '%':
                        buf.append("%25");
                        continue;
                    case '?':
                        buf.append("%3F");
                        continue;
                    case ';':
                        buf.append("%3B");
                        continue;
                    case '#':
                        buf.append("%23");
                        continue;
                    case '"':
                        buf.append("%22");
                        continue;
                    case '\'':
                        buf.append("%27");
                        continue;
                    case '<':
                        buf.append("%3C");
                        continue;
                    case '>':
                        buf.append("%3E");
                        continue;
                    case ' ':
                        buf.append("%20");
                        continue;
                    case '[':
                        buf.append("%5B");
                        continue;
                    case '\\':
                        buf.append("%5C");
                        continue;
                    case ']':
                        buf.append("%5D");
                        continue;
                    case '^':
                        buf.append("%5E");
                        continue;
                    case '`':
                        buf.append("%60");
                        continue;
                    case '{':
                        buf.append("%7B");
                        continue;
                    case '|':
                        buf.append("%7C");
                        continue;
                    case '}':
                        buf.append("%7D");
                        continue;
                    default:
                        if (c < 0)
                        {
                            buf.append('%');
                            TypeUtil.toHex(c, buf);
                        }
                        else
                            buf.append((char)c);
                }
            }
        }

        return buf;
    }

    /**
     * Encode a raw URI String and convert any raw spaces to
     * their "%20" equivalent.
     *
     * @param str input raw string
     * @return output with spaces converted to "%20"
     */
    public static String encodeSpaces(String str)
    {
        return StringUtil.replace(str, " ", "%20");
    }

    /**
     * Encode a raw String and convert any specific characters to their URI encoded equivalent.
     *
     * @param str input raw string
     * @param charsToEncode the list of raw characters that need to be encoded (if encountered)
     * @return output with specified characters encoded.
     */
    @SuppressWarnings("Duplicates")
    public static String encodeSpecific(String str, String charsToEncode)
    {
        if ((str == null) || (str.length() == 0))
            return null;

        if ((charsToEncode == null) || (charsToEncode.length() == 0))
            return str;

        char[] find = charsToEncode.toCharArray();
        int len = str.length();
        StringBuilder ret = new StringBuilder((int)(len * 0.20d));
        for (int i = 0; i < len; i++)
        {
            char c = str.charAt(i);
            boolean escaped = false;
            for (char f : find)
            {
                if (c == f)
                {
                    escaped = true;
                    ret.append('%');
                    int d = 0xf & ((0xF0 & c) >> 4);
                    ret.append((char)((d > 9 ? ('A' - 10) : '0') + d));
                    d = 0xf & c;
                    ret.append((char)((d > 9 ? ('A' - 10) : '0') + d));
                    break;
                }
            }
            if (!escaped)
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    /**
     * Decode a raw String and convert any specific URI encoded sequences into characters.
     *
     * @param str input raw string
     * @param charsToDecode the list of raw characters that need to be decoded (if encountered), leaving all other encoded sequences alone.
     * @return output with specified characters decoded.
     */
    @SuppressWarnings("Duplicates")
    public static String decodeSpecific(String str, String charsToDecode)
    {
        if ((str == null) || (str.length() == 0))
            return null;

        if ((charsToDecode == null) || (charsToDecode.length() == 0))
            return str;

        int idx = str.indexOf('%');
        if (idx == -1)
        {
            // no hits
            return str;
        }

        char[] find = charsToDecode.toCharArray();
        int len = str.length();
        Utf8StringBuilder ret = new Utf8StringBuilder(len);
        ret.append(str, 0, idx);

        for (int i = idx; i < len; i++)
        {
            char c = str.charAt(i);
            switch (c)
            {
                case '%':
                    if ((i + 2) < len)
                    {
                        char u = str.charAt(i + 1);
                        char l = str.charAt(i + 2);
                        char result = (char)(0xff & (TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(l)));
                        boolean decoded = false;
                        for (char f : find)
                        {
                            if (f == result)
                            {
                                ret.append(result);
                                decoded = true;
                                break;
                            }
                        }
                        if (decoded)
                        {
                            i += 2;
                        }
                        else
                        {
                            ret.append(c);
                        }
                    }
                    else
                    {
                        throw new IllegalArgumentException("Bad URI % encoding");
                    }
                    break;
                default:
                    ret.append(c);
                    break;
            }
        }
        return ret.toString();
    }

    /**
     * Encode a URI path.
     *
     * @param path The path the encode
     * @param buf StringBuilder to encode path into (or null)
     * @param encode String of characters to encode. % is always encoded.
     * @return The StringBuilder or null if no substitutions required.
     */
    public static StringBuilder encodeString(StringBuilder buf,
                                             String path,
                                             String encode)
    {
        if (buf == null)
        {
            for (int i = 0; i < path.length(); i++)
            {
                char c = path.charAt(i);
                if (c == '%' || encode.indexOf(c) >= 0)
                {
                    buf = new StringBuilder(path.length() << 1);
                    break;
                }
            }
            if (buf == null)
                return null;
        }

        for (int i = 0; i < path.length(); i++)
        {
            char c = path.charAt(i);
            if (c == '%' || encode.indexOf(c) >= 0)
            {
                buf.append('%');
                StringUtil.append(buf, (byte)(0xff & c), 16);
            }
            else
                buf.append(c);
        }

        return buf;
    }

    /* Decode a URI path and strip parameters
     */
    public static String decodePath(String path)
    {
        return decodePath(path, 0, path.length());
    }

    /* Decode a URI path and strip parameters of UTF-8 path
     */
    public static String decodePath(String path, int offset, int length)
    {
        try
        {
            Utf8StringBuilder builder = null;
            int end = offset + length;
            for (int i = offset; i < end; i++)
            {
                char c = path.charAt(i);
                switch (c)
                {
                    case '%':
                        if (builder == null)
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, offset, i - offset);
                        }
                        if ((i + 2) < end)
                        {
                            char u = path.charAt(i + 1);
                            if (u == 'u')
                            {
                                // TODO this is wrong. This is a codepoint not a char
                                builder.append((char)(0xffff & TypeUtil.parseInt(path, i + 2, 4, 16)));
                                i += 5;
                            }
                            else
                            {
                                builder.append((byte)(0xff & (TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(path.charAt(i + 2)))));
                                i += 2;
                            }
                        }
                        else
                        {
                            throw new IllegalArgumentException("Bad URI % encoding");
                        }

                        break;

                    case ';':
                        if (builder == null)
                        {
                            builder = new Utf8StringBuilder(path.length());
                            builder.append(path, offset, i - offset);
                        }

                        while (++i < end)
                        {
                            if (path.charAt(i) == '/')
                            {
                                builder.append('/');
                                break;
                            }
                        }

                        break;

                    default:
                        if (builder != null)
                            builder.append(c);
                        break;
                }
            }

            if (builder != null)
                return builder.toString();
            if (offset == 0 && length == path.length())
                return path;
            return path.substring(offset, end);
        }
        catch (NotUtf8Exception e)
        {
            LOG.warn(path.substring(offset, offset + length) + " " + e);
            LOG.debug(e);
            return decodeISO88591Path(path, offset, length);
        }
    }

    /* Decode a URI path and strip parameters of ISO-8859-1 path
     */
    private static String decodeISO88591Path(String path, int offset, int length)
    {
        StringBuilder builder = null;
        int end = offset + length;
        for (int i = offset; i < end; i++)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '%':
                    if (builder == null)
                    {
                        builder = new StringBuilder(path.length());
                        builder.append(path, offset, i - offset);
                    }
                    if ((i + 2) < end)
                    {
                        char u = path.charAt(i + 1);
                        if (u == 'u')
                        {
                            // TODO this is wrong. This is a codepoint not a char
                            builder.append((char)(0xffff & TypeUtil.parseInt(path, i + 2, 4, 16)));
                            i += 5;
                        }
                        else
                        {
                            builder.append((byte)(0xff & (TypeUtil.convertHexDigit(u) * 16 + TypeUtil.convertHexDigit(path.charAt(i + 2)))));
                            i += 2;
                        }
                    }
                    else
                    {
                        throw new IllegalArgumentException();
                    }

                    break;

                case ';':
                    if (builder == null)
                    {
                        builder = new StringBuilder(path.length());
                        builder.append(path, offset, i - offset);
                    }
                    while (++i < end)
                    {
                        if (path.charAt(i) == '/')
                        {
                            builder.append('/');
                            break;
                        }
                    }
                    break;

                default:
                    if (builder != null)
                        builder.append(c);
                    break;
            }
        }

        if (builder != null)
            return builder.toString();
        if (offset == 0 && length == path.length())
            return path;
        return path.substring(offset, end);
    }

    /**
     * Add two encoded URI path segments.
     * Handles null and empty paths, path and query params
     * (eg ?a=b or ;JSESSIONID=xxx) and avoids duplicate '/'
     *
     * @param p1 URI path segment (should be encoded)
     * @param p2 URI path segment (should be encoded)
     * @return Legally combined path segments.
     */
    public static String addEncodedPaths(String p1, String p2)
    {
        if (p1 == null || p1.length() == 0)
        {
            if (p1 != null && p2 == null)
                return p1;
            return p2;
        }
        if (p2 == null || p2.length() == 0)
            return p1;

        int split = p1.indexOf(';');
        if (split < 0)
            split = p1.indexOf('?');
        if (split == 0)
            return p2 + p1;
        if (split < 0)
            split = p1.length();

        StringBuilder buf = new StringBuilder(p1.length() + p2.length() + 2);
        buf.append(p1);

        if (buf.charAt(split - 1) == '/')
        {
            if (p2.startsWith(URIUtil.SLASH))
            {
                buf.deleteCharAt(split - 1);
                buf.insert(split - 1, p2);
            }
            else
                buf.insert(split, p2);
        }
        else
        {
            if (p2.startsWith(URIUtil.SLASH))
                buf.insert(split, p2);
            else
            {
                buf.insert(split, '/');
                buf.insert(split + 1, p2);
            }
        }

        return buf.toString();
    }

    /**
     * Add two Decoded URI path segments.
     * Handles null and empty paths.  Path and query params (eg ?a=b or
     * ;JSESSIONID=xxx) are not handled
     *
     * @param p1 URI path segment (should be decoded)
     * @param p2 URI path segment (should be decoded)
     * @return Legally combined path segments.
     */
    public static String addPaths(String p1, String p2)
    {
        if (p1 == null || p1.length() == 0)
        {
            if (p1 != null && p2 == null)
                return p1;
            return p2;
        }
        if (p2 == null || p2.length() == 0)
            return p1;

        boolean p1EndsWithSlash = p1.endsWith(SLASH);
        boolean p2StartsWithSlash = p2.startsWith(SLASH);

        if (p1EndsWithSlash && p2StartsWithSlash)
        {
            if (p2.length() == 1)
                return p1;
            if (p1.length() == 1)
                return p2;
        }

        StringBuilder buf = new StringBuilder(p1.length() + p2.length() + 2);
        buf.append(p1);

        if (p1.endsWith(SLASH))
        {
            if (p2.startsWith(SLASH))
                buf.setLength(buf.length() - 1);
        }
        else
        {
            if (!p2.startsWith(SLASH))
                buf.append(SLASH);
        }
        buf.append(p2);

        return buf.toString();
    }

    /**
     * Return the parent Path.
     * Treat a URI like a directory path and return the parent directory.
     *
     * @param p the path to return a parent reference to
     * @return the parent path of the URI
     */
    public static String parentPath(String p)
    {
        if (p == null || URIUtil.SLASH.equals(p))
            return null;
        int slash = p.lastIndexOf('/', p.length() - 2);
        if (slash >= 0)
            return p.substring(0, slash + 1);
        return null;
    }

    /**
     * Convert a decoded path to a canonical form.
     * <p>
     * All instances of "." and ".." are factored out.
     * </p>
     * <p>
     * Null is returned if the path tries to .. above its root.
     * </p>
     *
     * @param path the path to convert, decoded, with path separators '/' and no queries.
     * @return the canonical path, or null if path traversal above root.
     */
    public static String canonicalPath(String path)
    {
        if (path == null || path.isEmpty())
            return path;

        boolean slash = true;
        int end = path.length();
        int i = 0;

        loop:
        while (i < end)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '/':
                    slash = true;
                    break;

                case '.':
                    if (slash)
                        break loop;
                    slash = false;
                    break;

                default:
                    slash = false;
            }

            i++;
        }

        if (i == end)
            return path;

        StringBuilder canonical = new StringBuilder(path.length());
        canonical.append(path, 0, i);

        int dots = 1;
        i++;
        while (i <= end)
        {
            char c = i < end ? path.charAt(i) : '\0';
            switch (c)
            {
                case '\0':
                case '/':
                    switch (dots)
                    {
                        case 0:
                            if (c != '\0')
                                canonical.append(c);
                            break;

                        case 1:
                            break;

                        case 2:
                            if (canonical.length() < 2)
                                return null;
                            canonical.setLength(canonical.length() - 1);
                            canonical.setLength(canonical.lastIndexOf("/") + 1);
                            break;

                        default:
                            while (dots-- > 0)
                            {
                                canonical.append('.');
                            }
                            if (c != '\0')
                                canonical.append(c);
                    }

                    slash = true;
                    dots = 0;
                    break;

                case '.':
                    if (dots > 0)
                        dots++;
                    else if (slash)
                        dots = 1;
                    else
                        canonical.append('.');
                    slash = false;
                    break;

                default:
                    while (dots-- > 0)
                    {
                        canonical.append('.');
                    }
                    canonical.append(c);
                    dots = 0;
                    slash = false;
            }

            i++;
        }
        return canonical.toString();
    }

    /**
     * Convert a path to a cananonical form.
     * <p>
     * All instances of "." and ".." are factored out.
     * </p>
     * <p>
     * Null is returned if the path tries to .. above its root.
     * </p>
     *
     * @param path the path to convert (expects URI/URL form, encoded, and with path separators '/')
     * @return the canonical path, or null if path traversal above root.
     */
    public static String canonicalEncodedPath(String path)
    {
        if (path == null || path.isEmpty())
            return path;

        boolean slash = true;
        int end = path.length();
        int i = 0;

        loop:
        while (i < end)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '/':
                    slash = true;
                    break;

                case '.':
                    if (slash)
                        break loop;
                    slash = false;
                    break;

                case '?':
                    return path;

                default:
                    slash = false;
            }

            i++;
        }

        if (i == end)
            return path;

        StringBuilder canonical = new StringBuilder(path.length());
        canonical.append(path, 0, i);

        int dots = 1;
        i++;
        while (i <= end)
        {
            char c = i < end ? path.charAt(i) : '\0';
            switch (c)
            {
                case '\0':
                case '/':
                case '?':
                    switch (dots)
                    {
                        case 0:
                            if (c != '\0')
                                canonical.append(c);
                            break;

                        case 1:
                            if (c == '?')
                                canonical.append(c);
                            break;

                        case 2:
                            if (canonical.length() < 2)
                                return null;
                            canonical.setLength(canonical.length() - 1);
                            canonical.setLength(canonical.lastIndexOf("/") + 1);
                            if (c == '?')
                                canonical.append(c);
                            break;
                        default:
                            while (dots-- > 0)
                            {
                                canonical.append('.');
                            }
                            if (c != '\0')
                                canonical.append(c);
                    }

                    slash = true;
                    dots = 0;
                    break;

                case '.':
                    if (dots > 0)
                        dots++;
                    else if (slash)
                        dots = 1;
                    else
                        canonical.append('.');
                    slash = false;
                    break;

                default:
                    while (dots-- > 0)
                    {
                        canonical.append('.');
                    }
                    canonical.append(c);
                    dots = 0;
                    slash = false;
            }

            i++;
        }
        return canonical.toString();
    }

    /**
     * Convert a path to a compact form.
     * All instances of "//" and "///" etc. are factored out to single "/"
     *
     * @param path the path to compact
     * @return the compacted path
     */
    public static String compactPath(String path)
    {
        if (path == null || path.length() == 0)
            return path;

        int state = 0;
        int end = path.length();
        int i = 0;

        loop:
        while (i < end)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '?':
                    return path;
                case '/':
                    state++;
                    if (state == 2)
                        break loop;
                    break;
                default:
                    state = 0;
            }
            i++;
        }

        if (state < 2)
            return path;

        StringBuilder buf = new StringBuilder(path.length());
        buf.append(path, 0, i);

        loop2:
        while (i < end)
        {
            char c = path.charAt(i);
            switch (c)
            {
                case '?':
                    buf.append(path, i, end);
                    break loop2;
                case '/':
                    if (state++ == 0)
                        buf.append(c);
                    break;
                default:
                    state = 0;
                    buf.append(c);
            }
            i++;
        }

        return buf.toString();
    }

    /**
     * @param uri URI
     * @return True if the uri has a scheme
     */
    public static boolean hasScheme(String uri)
    {
        for (int i = 0; i < uri.length(); i++)
        {
            char c = uri.charAt(i);
            if (c == ':')
            {
                return true;
            }
            if (!(c >= 'a' && c <= 'z' ||
                c >= 'A' && c <= 'Z' ||
                (i > 0 && (c >= '0' && c <= '9' || c == '.' || c == '+' || c == '-'))))
            {
                break;
            }
        }
        return false;
    }

    /**
     * Create a new URI from the arguments, handling IPv6 host encoding and default ports
     *
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     * @param path the URI path
     * @param query the URI query
     * @return A String URI
     */
    public static String newURI(String scheme, String server, int port, String path, String query)
    {
        StringBuilder builder = newURIBuilder(scheme, server, port);
        builder.append(path);
        if (query != null && query.length() > 0)
            builder.append('?').append(query);
        return builder.toString();
    }

    /**
     * Create a new URI StringBuilder from the arguments, handling IPv6 host encoding and default ports
     *
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     * @return a StringBuilder containing URI prefix
     */
    public static StringBuilder newURIBuilder(String scheme, String server, int port)
    {
        StringBuilder builder = new StringBuilder();
        appendSchemeHostPort(builder, scheme, server, port);
        return builder;
    }

    /**
     * Append scheme, host and port URI prefix, handling IPv6 address encoding and default ports
     *
     * @param url StringBuilder to append to
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     */
    public static void appendSchemeHostPort(StringBuilder url, String scheme, String server, int port)
    {
        url.append(scheme).append("://").append(HostPort.normalizeHost(server));

        if (port > 0)
        {
            switch (scheme)
            {
                case "http":
                    if (port != 80)
                        url.append(':').append(port);
                    break;

                case "https":
                    if (port != 443)
                        url.append(':').append(port);
                    break;

                default:
                    url.append(':').append(port);
            }
        }
    }

    /**
     * Append scheme, host and port URI prefix, handling IPv6 address encoding and default ports
     *
     * @param url StringBuffer to append to
     * @param scheme the URI scheme
     * @param server the URI server
     * @param port the URI port
     */
    public static void appendSchemeHostPort(StringBuffer url, String scheme, String server, int port)
    {
        synchronized (url)
        {
            url.append(scheme).append("://").append(HostPort.normalizeHost(server));

            if (port > 0)
            {
                switch (scheme)
                {
                    case "http":
                        if (port != 80)
                            url.append(':').append(port);
                        break;

                    case "https":
                        if (port != 443)
                            url.append(':').append(port);
                        break;

                    default:
                        url.append(':').append(port);
                }
            }
        }
    }

    public static boolean equalsIgnoreEncodings(String uriA, String uriB)
    {
        int lenA = uriA.length();
        int lenB = uriB.length();
        int a = 0;
        int b = 0;

        while (a < lenA && b < lenB)
        {
            int oa = uriA.charAt(a++);
            int ca = oa;
            if (ca == '%')
                ca = TypeUtil.convertHexDigit(uriA.charAt(a++)) * 16 + TypeUtil.convertHexDigit(uriA.charAt(a++));

            int ob = uriB.charAt(b++);
            int cb = ob;
            if (cb == '%')
                cb = TypeUtil.convertHexDigit(uriB.charAt(b++)) * 16 + TypeUtil.convertHexDigit(uriB.charAt(b++));

            if (ca == '/' && oa != ob)
                return false;

            if (ca != cb)
                return URIUtil.decodePath(uriA).equals(URIUtil.decodePath(uriB));
        }
        return a == lenA && b == lenB;
    }

    public static boolean equalsIgnoreEncodings(URI uriA, URI uriB)
    {
        if (uriA.equals(uriB))
            return true;

        if (uriA.getScheme() == null)
        {
            if (uriB.getScheme() != null)
                return false;
        }
        else if (!uriA.getScheme().equalsIgnoreCase(uriB.getScheme()))
            return false;

        if ("jar".equalsIgnoreCase(uriA.getScheme()))
        {
            // at this point we know that both uri's are "jar:"
            URI uriAssp = URI.create(uriA.getSchemeSpecificPart());
            URI uriBssp = URI.create(uriB.getSchemeSpecificPart());
            return equalsIgnoreEncodings(uriAssp, uriBssp);
        }

        if (uriA.getAuthority() == null)
        {
            if (uriB.getAuthority() != null)
                return false;
        }
        else if (!uriA.getAuthority().equals(uriB.getAuthority()))
            return false;

        return equalsIgnoreEncodings(uriA.getPath(), uriB.getPath());
    }

    /**
     * @param uri A URI to add the path to
     * @param path A decoded path element
     * @return URI with path added.
     */
    public static URI addPath(URI uri, String path)
    {
        String base = uri.toASCIIString();
        StringBuilder buf = new StringBuilder(base.length() + path.length() * 3);
        buf.append(base);
        if (buf.charAt(base.length() - 1) != '/')
            buf.append('/');

        int offset = path.charAt(0) == '/' ? 1 : 0;
        encodePath(buf, path, offset);

        return URI.create(buf.toString());
    }

    public static URI getJarSource(URI uri)
    {
        try
        {
            if (!"jar".equals(uri.getScheme()))
                return uri;
            // Get SSP (retaining encoded form)
            String s = uri.getRawSchemeSpecificPart();
            int bangSlash = s.indexOf("!/");
            if (bangSlash >= 0)
                s = s.substring(0, bangSlash);
            return new URI(s);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    public static String getJarSource(String uri)
    {
        if (!uri.startsWith("jar:"))
            return uri;
        int bangSlash = uri.indexOf("!/");
        return (bangSlash >= 0) ? uri.substring(4, bangSlash) : uri.substring(4);
    }
}
