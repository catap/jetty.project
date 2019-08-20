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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static org.eclipse.jetty.util.TypeUtil.convertHexDigit;

/**
 * Handles coding of MIME  "x-www-form-urlencoded".
 * <p>
 * This class handles the encoding and decoding for either
 * the query string of a URL or the _content of a POST HTTP request.
 * </p>
 * <b>Notes</b>
 * <p>
 * The UTF-8 charset is assumed, unless otherwise defined by either
 * passing a parameter or setting the "org.eclipse.jetty.util.UrlEncoding.charset"
 * System property.
 * </p>
 * <p>
 * The hashtable either contains String single values, vectors
 * of String or arrays of Strings.
 * </p>
 * <p>
 * This class is only partially synchronised.  In particular, simple
 * get operations are not protected from concurrent updates.
 * </p>
 *
 * @see java.net.URLEncoder
 */
@SuppressWarnings("serial")
public class UrlEncoded extends MultiMap<String> implements Cloneable
{
    static final Logger LOG = Log.getLogger(UrlEncoded.class);

    public static final CharsetEncoder ENCODER;

    public static final CharsetDecoder DECODER;

    public static final CharsetEncoder SYSTEM_ENCODER = defaultEncoder(Charset.defaultCharset());

    public static final CharsetDecoder SYSTEM_DECODER = defaultDecoder(Charset.defaultCharset());

    public static final CharsetEncoder UTF_8_ENCODER = defaultEncoder(StandardCharsets.UTF_8);

    public static final CharsetDecoder UTF_8_DECODER = defaultDecoder(StandardCharsets.UTF_8);

    public static final CharsetEncoder ISO_8859_1_ENCODER = defaultEncoder(StandardCharsets.ISO_8859_1);

    public static final CharsetDecoder ISO_8859_1_DECODER = defaultDecoder(StandardCharsets.ISO_8859_1);

    static
    {
        Charset charset;
        try
        {
            String charsetName = System.getProperty("org.eclipse.jetty.util.UrlEncoding.charset");
            charset = charsetName == null ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
        }
        catch (Exception e)
        {
            LOG.warn(e);
            charset = StandardCharsets.UTF_8;
        }
        ENCODER = defaultEncoder(charset);

        DECODER = defaultDecoder(charset);
    }

    private static CharsetEncoder defaultEncoder(Charset charset) {
        return charset.newEncoder()
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .onMalformedInput(CodingErrorAction.REPORT);
    }

    private static CharsetDecoder defaultDecoder(Charset charset) {
        return charset.newDecoder()
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .onMalformedInput(CodingErrorAction.REPORT);
    }

    public UrlEncoded(UrlEncoded url)
    {
        super(url);
    }

    public UrlEncoded()
    {
    }

    public UrlEncoded(String query) throws CharacterCodingException
    {
        decodeTo(query, this, DECODER);
    }

    public void decode(String query) throws CharacterCodingException
    {
        decodeTo(query, this, DECODER);
    }

    public void decode(String query, Charset charset) throws CharacterCodingException
    {
        decodeTo(query, this, charset == null ? null : defaultDecoder(charset));
    }

    public void decode(String query, CharsetDecoder decoder) throws CharacterCodingException
    {
        decodeTo(query, this, decoder);
    }


    /**
     * Encode MultiMap with % encoding for UTF8 sequences.
     *
     * @return the MultiMap as a string with % encoding
     */
    public String encode() throws CharacterCodingException
    {
        return encode(this, ENCODER, false);
    }

    /**
     * Encode MultiMap with % encoding for arbitrary Charset sequences.
     *
     * @param charset the charset to use for encoding
     * @return the MultiMap as a string encoded with % encodings
     */
    public String encode(Charset charset) throws CharacterCodingException
    {
        return encode(charset, false);
    }

    /**
     * Encode MultiMap with % encoding for arbitrary Charset sequences.
     *
     * @param encoder the charset encoder to use for encoding
     * @return the MultiMap as a string encoded with % encodings
     */
    public String encode(CharsetEncoder encoder) throws CharacterCodingException
    {
        return encode(this, encoder, false);
    }

    /**
     * Encode MultiMap with % encoding.
     *
     * @param charset the charset to encode with
     * @param equalsForNullValue if True, then an '=' is always used, even
     * for parameters without a value. e.g. <code>"blah?a=&amp;b=&amp;c="</code>.
     * @return the MultiMap as a string encoded with % encodings
     */
    public synchronized String encode(Charset charset, boolean equalsForNullValue) throws CharacterCodingException
    {
        return encode(this, charset, equalsForNullValue);
    }

    /**
     * Encode MultiMap with % encoding.
     *
     * @param map the map to encode
     * @param charset the charset to use for encoding (uses default encoding if null)
     * @param equalsForNullValue if True, then an '=' is always used, even
     * for parameters without a value. e.g. <code>"blah?a=&amp;b=&amp;c="</code>.
     * @return the MultiMap as a string encoded with % encodings.
     */
    public static String encode(MultiMap<String> map, Charset charset, boolean equalsForNullValue) throws CharacterCodingException
    {
        return encode(map, charset == null ? null: defaultEncoder(charset), equalsForNullValue);
    }

    /**
     * Encode MultiMap with % encoding.
     *
     * @param map the map to encode
     * @param encoder the charset encoder to use for encoding (uses default encoding if null)
     * @param equalsForNullValue if True, then an '=' is always used, even
     * for parameters without a value. e.g. <code>"blah?a=&amp;b=&amp;c="</code>.
     * @return the MultiMap as a string encoded with % encodings.
     */
    public static String encode(MultiMap<String> map, CharsetEncoder encoder, boolean equalsForNullValue) throws CharacterCodingException
    {
        if (encoder == null)
            encoder = ENCODER;

        StringBuilder result = new StringBuilder(128);

        boolean delim = false;
        for (Map.Entry<String, List<String>> entry : map.entrySet())
        {
            String key = entry.getKey();
            List<String> list = entry.getValue();
            int s = list.size();

            if (delim)
            {
                result.append('&');
            }

            if (s == 0)
            {
                result.append(encodeString(key, encoder));
                if (equalsForNullValue)
                    result.append('=');
            }
            else
            {
                for (int i = 0; i < s; i++)
                {
                    if (i > 0)
                        result.append('&');
                    String val = list.get(i);
                    result.append(encodeString(key, encoder));

                    if (val != null)
                    {
                        String str = val;
                        if (str.length() > 0)
                        {
                            result.append('=');
                            result.append(encodeString(str, encoder));
                        }
                        else if (equalsForNullValue)
                            result.append('=');
                    }
                    else if (equalsForNullValue)
                        result.append('=');
                }
            }
            delim = true;
        }
        return result.toString();
    }

    /**
     * Decoded parameters to Map.
     *
     * @param content the string containing the encoded parameters
     * @param map the MultiMap to put parsed query parameters into
     * @param charset the charset to use for decoding
     */
    public static void decodeTo(String content, MultiMap<String> map, String charset) throws CharacterCodingException
    {
        decodeTo(content, map, charset == null ? null : Charset.forName(charset));
    }

    /**
     * Decoded parameters to Map.
     *
     * @param content the string containing the encoded parameters
     * @param map the MultiMap to put parsed query parameters into
     * @param charset the charset to use for decoding
     */
    public static void decodeTo(String content, MultiMap<String> map, Charset charset) throws CharacterCodingException
    {
        decodeTo(content, map, charset == null ? null : defaultDecoder(charset));
    }

    /**
     * Decoded parameters to Map.
     *
     * @param content the string containing the encoded parameters
     * @param map the MultiMap to put parsed query parameters into
     * @param decoder the charset decoder to use for decoding
     */
    public static void decodeTo(String content, MultiMap<String> map, CharsetDecoder decoder) throws CharacterCodingException
    {
        decodeTo(content, 0, content.length(), map, decoder);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param content the string containing the encoded parameters
     * @param map the MultiMap to put parsed query parameters into
     * @param offset the offset within raw to decode from
     * @param length the length of the section to decode
     * @param decoder the charset decoder to use for decoding
     */
    public static void decodeTo(String content, int offset, int length, MultiMap<String> map, CharsetDecoder decoder) throws CharacterCodingException
    {
        if (decoder == null)
            decoder = DECODER;

        ByteBuffer byteBuffer = SYSTEM_ENCODER.encode(CharBuffer.wrap(content, offset, length));

        ByteArrayOutputStream2 buffer = new ByteArrayOutputStream2();
        synchronized (map)
        {
            String key = null;
            String value = null;

            for (int i = 0; i < byteBuffer.remaining(); i++)
            {
                byte b = byteBuffer.get(i);
                switch (b)
                {
                    case '&':
                        value = decoder.decode(buffer.getByteBuffer()).toString();
                        buffer.reset();
                        if (key != null)
                        {
                            map.add(key, value);
                        }
                        else if (value != null && value.length() > 0)
                        {
                            map.add(value, "");
                        }
                        key = null;
                        value = null;
                        break;

                    case '=':
                        if (key != null)
                        {
                            buffer.write(b);
                            break;
                        }
                        key = decoder.decode(buffer.getByteBuffer()).toString();
                        buffer.reset();
                        break;

                    case '+':
                        buffer.write((byte)' ');
                        break;

                    case '%':
                        if (i + 2 < byteBuffer.remaining())
                        {
                            byte hi = byteBuffer.get(++i);
                            byte lo = byteBuffer.get(++i);
                            buffer.write(decodeHexByte((char) hi, (char) lo));
                        }
                        else
                        {
                            throw new Utf8Appendable.NotUtf8Exception("Incomplete % encoding");
                        }
                        break;

                    default:
                        buffer.write(b);
                        break;
                }
            }

            if (key != null)
            {
                value = decoder.decode(buffer.getByteBuffer()).toString();
                buffer.reset();
                map.add(key, value);
            }
            else if (buffer.size() > 0)
            {
                map.add(decoder.decode(buffer.getByteBuffer()).toString(), "");
            }
        }
    }

    public static void decodeUtf8To(String query, MultiMap<String> map) throws CharacterCodingException
    {
        decodeTo(query, 0, query.length(), map, UTF_8_DECODER);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param query the string containing the encoded parameters
     * @param offset the offset within raw to decode from
     * @param length the length of the section to decode
     * @param map the {@link MultiMap} to populate
     */
    public static void decodeUtf8To(String query, int offset, int length, MultiMap<String> map) throws CharacterCodingException
    {
        decodeTo(query, offset, length, map, UTF_8_DECODER);
    }

    /**
     * Decoded parameters to MultiMap, using ISO8859-1 encodings.
     *
     * @param in InputSteam to read
     * @param map MultiMap to add parameters to
     * @param maxLength maximum length of form to read or -1 for no limit
     * @param maxKeys maximum number of keys to read or -1 for no limit
     * @throws IOException if unable to decode the InputStream as ISO8859-1
     */
    public static void decode88591To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys)
        throws IOException
    {
        decodeTo(in, map, ISO_8859_1_DECODER, maxLength, maxKeys);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in InputSteam to read
     * @param map MultiMap to add parameters to
     * @param maxLength maximum form length to decode or -1 for no limit
     * @param maxKeys the maximum number of keys to read or -1 for no limit
     * @throws IOException if unable to decode the input stream
     */
    public static void decodeUtf8To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys)
        throws IOException
    {
        decodeTo(in, map, UTF_8_DECODER, maxLength, maxKeys);
    }

    public static void decodeUtf16To(InputStream in, MultiMap<String> map, int maxLength, int maxKeys) throws IOException
    {
        InputStreamReader input = new InputStreamReader(in, StandardCharsets.UTF_16);
        StringWriter buf = new StringWriter(8192);
        IO.copy(input, buf, maxLength);

        // TODO implement maxKeys
        decodeTo(buf.getBuffer().toString(), map, StandardCharsets.UTF_16);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in the stream containing the encoded parameters
     * @param map the MultiMap to decode into
     * @param charset the charset to use for decoding
     * @param maxLength the maximum length of the form to decode or -1 for no limit
     * @param maxKeys the maximum number of keys to decode or -1 for no limit
     * @throws IOException if unable to decode the input stream
     */
    public static void decodeTo(InputStream in, MultiMap<String> map, String charset, int maxLength, int maxKeys)
        throws IOException
    {
        decodeTo(in, map, Charset.forName(charset), maxLength, maxKeys);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in the stream containing the encoded parameters
     * @param map the MultiMap to decode into
     * @param charset the charset to use for decoding
     * @param maxLength the maximum length of the form to decode
     * @param maxKeys the maximum number of keys to decode
     * @throws IOException if unable to decode input stream
     */
    public static void decodeTo(InputStream in, MultiMap<String> map, Charset charset, int maxLength, int maxKeys)
            throws IOException
    {
        decodeTo(in, map, charset == null ? null : defaultDecoder(charset), maxLength, maxKeys);
    }

    /**
     * Decoded parameters to Map.
     *
     * @param in the stream containing the encoded parameters
     * @param map the MultiMap to decode into
     * @param decoder the charset decoder to use for decoding
     * @param maxLength the maximum length of the form to decode
     * @param maxKeys the maximum number of keys to decode
     * @throws IOException if unable to decode input stream
     */
    public static void decodeTo(InputStream in, MultiMap<String> map, CharsetDecoder decoder, int maxLength, int maxKeys)
        throws IOException
    {
        if (decoder == null)
            decoder = DECODER;

        synchronized (map)
        {
            ByteArrayOutputStream2 buffer = new ByteArrayOutputStream2();
            String key = null;
            String value = null;

            int b;

            int totalLength = 0;
            while ((b = in.read()) >= 0)
            {
                switch ((char)b)
                {
                    case '&':
                        value = decoder.decode(buffer.getByteBuffer()).toString();
                        buffer.reset();
                        if (key != null)
                        {
                            map.add(key, value);
                        }
                        else if (value != null && value.length() > 0)
                        {
                            map.add(value, "");
                        }
                        key = null;
                        value = null;
                        checkMaxKeys(map, maxKeys);
                        break;

                    case '=':
                        if (key != null)
                        {
                            buffer.write((byte)b);
                            break;
                        }
                        key = decoder.decode(buffer.getByteBuffer()).toString();
                        buffer.reset();
                        break;

                    case '+':
                        buffer.write((byte)' ');
                        break;

                    case '%':
                        char code0 = (char)in.read();
                        char code1 = (char)in.read();
                        buffer.write(decodeHexByte(code0, code1));
                        break;

                    default:
                        buffer.write((byte)b);
                        break;
                }
                checkMaxLength(++totalLength, maxLength);
            }

            if (key != null)
            {
                value = decoder.decode(buffer.getByteBuffer()).toString();
                buffer.reset();
                map.add(key, value);
            }
            else if (buffer.size() > 0)
            {
                map.add(decoder.decode(buffer.getByteBuffer()).toString(), "");
            }
            checkMaxKeys(map, maxKeys);
        }
    }

    private static void checkMaxKeys(MultiMap<String> map, int maxKeys)
    {
        int size = map.size();
        if (maxKeys >= 0 && size > maxKeys)
            throw new IllegalStateException(String.format("Form with too many keys [%d > %d]", size, maxKeys));
    }

    private static void checkMaxLength(int length, int maxLength)
    {
        if (maxLength >= 0 && length > maxLength)
            throw new IllegalStateException("Form is larger than max length " + maxLength);
    }

    /**
     * Decode String with % encoding.
     * This method makes the assumption that the majority of calls
     * will need no decoding.
     *
     * @param encoded the encoded string to decode
     * @return the decoded string
     */
    public static String decodeString(String encoded) throws CharacterCodingException
    {
        return decodeString(CharBuffer.wrap(encoded), DECODER);
    }

    /**
     * Decode String with % encoding.
     * This method makes the assumption that the majority of calls
     * will need no decoding.
     *
     * @param encoded the encoded string to decode
     * @param offset the offset in the encoded string to decode from
     * @param length the length of characters in the encoded string to decode
     * @param charset the charset to use for decoding
     * @return the decoded string
     */
    public static String decodeString(String encoded, int offset, int length, Charset charset) throws CharacterCodingException
    {
        StringBuffer sb = new StringBuffer();
        sb.append(encoded, 0, offset);
        sb.append(decodeString(CharBuffer.wrap(encoded, offset, 0), charset == null ? DECODER : defaultDecoder(charset)));
        sb.append(encoded, offset + length, encoded.length() - length);

        return sb.toString();
    }

    /**
     * Decode String with % encoding.
     * This method makes the assumption that the majority of calls
     * will need no decoding.
     *
     * @param charBuffer the encoded charbuffer to decode
     * @param decoder the charset to use for decoding
     * @return the decoded string
     */
    public static String decodeString(CharBuffer charBuffer, CharsetDecoder decoder) throws CharacterCodingException
    {
        if (decoder == null) {
            decoder = DECODER;
        }

        ByteBuffer byteBuffer = SYSTEM_ENCODER.encode(charBuffer);

        ByteArrayOutputStream2 buffer = new ByteArrayOutputStream2();

        for (int i = 0; i < byteBuffer.remaining(); i++)
        {
            byte b = byteBuffer.get(i);
            if (b == '+')
            {
                buffer.write(' ');
            }
            else if (b == '%')
            {
                if ((i + 2) < byteBuffer.remaining())
                {
                    int o = i + 1;
                    i += 2;
                    b = (byte)TypeUtil.parseInt(byteBuffer, o, 2, 16);
                    buffer.write(b);
                }
                else
                {
                    buffer.write(Utf8Appendable.REPLACEMENT);
                    break;
                }
            }
            else
                buffer.write(b);
        }

        return decoder.decode(buffer.getByteBuffer()).toString();
    }

    private static char decodeHexChar(int hi, int lo)
    {
        try
        {
            return (char)((convertHexDigit(hi) << 4) + convertHexDigit(lo));
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Not valid encoding '%" + (char)hi + (char)lo + "'");
        }
    }

    private static byte decodeHexByte(char hi, char lo)
    {
        try
        {
            return (byte)((convertHexDigit(hi) << 4) + convertHexDigit(lo));
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Not valid encoding '%" + hi + lo + "'");
        }
    }

    /**
     * Perform URL encoding.
     *
     * @param string the string to encode
     * @return encoded string.
     */
    public static String encodeString(String string) throws CharacterCodingException
    {
        return encodeString(string, ENCODER);
    }

    /**
     * Perform URL encoding.
     *
     * @param string the string to encode
     * @param charset the charset to use for encoding
     * @return encoded string.
     */
    public static String encodeString(String string, Charset charset) throws CharacterCodingException
    {
        return encodeString(string, charset == null ? null : defaultEncoder(charset));
    }

    /**
     * Perform URL encoding.
     *
     * @param string the string to encode
     * @param encoder the charset encoder to use for encoding
     * @return encoded string.
     */
    public static String encodeString(String string, CharsetEncoder encoder) throws CharacterCodingException
    {
        if (encoder == null)
            encoder = ENCODER;

        CharBuffer charBuffer = CharBuffer.wrap(string);

        ByteBuffer byteBuffer = encoder.encode(charBuffer);

        int len = byteBuffer.remaining();
        ByteArrayOutputStream2 encoded = new ByteArrayOutputStream2();
        boolean noEncode = true;

        for (int i = 0; i < len; i++)
        {
            byte b = byteBuffer.get(i);

            if (b == ' ')
            {
                noEncode = false;
                encoded.write('+');
            }
            else if (b >= 'a' && b <= 'z' ||
                b >= 'A' && b <= 'Z' ||
                b >= '0' && b <= '9')
            {
                encoded.write(b);
            }
            else
            {
                noEncode = false;
                encoded.write('%');
                byte nibble = (byte)((b & 0xf0) >> 4);
                if (nibble >= 10)
                    encoded.write((byte)('A' + nibble - 10));
                else
                    encoded.write((byte)('0' + nibble));
                nibble = (byte)(b & 0xf);
                if (nibble >= 10)
                    encoded.write((byte)('A' + nibble - 10));
                else
                    encoded.write((byte)('0' + nibble));
            }
        }

        if (noEncode)
            return string;

        return new String(encoded.toByteArray());
    }

    /**
     *
     */
    @Override
    public Object clone()
    {
        return new UrlEncoded(this);
    }
}
