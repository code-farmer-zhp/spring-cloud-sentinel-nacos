/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.demo.controller.sentinel.command.http;

import com.alibaba.csp.sentinel.command.CommandHandler;
import com.alibaba.csp.sentinel.command.CommandRequest;
import com.alibaba.csp.sentinel.command.CommandResponse;
import com.alibaba.csp.sentinel.config.SentinelConfig;
import com.alibaba.csp.sentinel.transport.log.CommandCenterLog;
import com.alibaba.csp.sentinel.transport.util.HttpCommandUtils;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.example.demo.controller.sentinel.command.exception.RequestException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The task handles incoming command request in HTTP protocol.
 *
 * @author youji.zj
 * @author Eric Zhao
 * @author Jason Joo
 */
public class HttpEventTask implements Runnable {

    public static final String SERVER_ERROR_MESSAGE = "Command server error";
    public static final String INVALID_COMMAND_MESSAGE = "Invalid command";

    private HttpServletRequest servletRequest;
    private HttpServletResponse servletResponse;

    private boolean writtenHead = false;

    public HttpEventTask(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        this.servletRequest = servletRequest;
        this.servletResponse = servletResponse;
    }

    @Override
    public void run() {
        PrintWriter printWriter = null;
        InputStream inputStream = null;
        try {
            long start = System.currentTimeMillis();
            inputStream = new BufferedInputStream(servletRequest.getInputStream());
            OutputStream outputStream = servletResponse.getOutputStream();

            printWriter = new PrintWriter(
                    new OutputStreamWriter(outputStream, Charset.forName(SentinelConfig.charset())));

            String firstLine = servletRequest.getRequestURI();
            CommandCenterLog.info("[SimpleHttpCommandCenter] Socket income: " + firstLine);
            CommandRequest request = processQueryString(servletRequest);

            if (servletRequest.getMethod().equalsIgnoreCase("post")) {
                // Deal with post method
                processPostRequest(inputStream, request);
            }

            // Validate the target command.
            String commandName = HttpCommandUtils.getTarget(request);
            if (StringUtil.isBlank(commandName)) {
                writeResponse(printWriter, StatusCode.BAD_REQUEST, INVALID_COMMAND_MESSAGE);
                return;
            }

            // Find the matching command handler.
            CommandHandler<?> commandHandler = SimpleHttpCommandCenter.getHandler(commandName);
            if (commandHandler != null) {
                CommandResponse<?> response = commandHandler.handle(request);
                handleResponse(response, printWriter);
            } else {
                // No matching command handler.
                writeResponse(printWriter, StatusCode.BAD_REQUEST, "Unknown command `" + commandName + '`');
            }

            long cost = System.currentTimeMillis() - start;
            CommandCenterLog.info("[SimpleHttpCommandCenter] Deal a socket task: " + firstLine
                    + ", time cost: " + cost + " ms");
        } catch (RequestException e) {
            writeResponse(printWriter, e.getStatusCode(), e.getMessage());
        } catch (Throwable e) {
            CommandCenterLog.warn("[SimpleHttpCommandCenter] CommandCenter error", e);
            try {
                if (printWriter != null) {
                    String errorMessage = SERVER_ERROR_MESSAGE;
                    e.printStackTrace();
                    if (!writtenHead) {
                        writeResponse(printWriter, StatusCode.INTERNAL_SERVER_ERROR, errorMessage);
                    } else {
                        printWriter.println(errorMessage);
                    }
                    printWriter.flush();
                }
            } catch (Exception e1) {
                CommandCenterLog.warn("Failed to write error response", e1);
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        int data;
        while (true) {
            data = in.read();
            if (data < 0) {
                break;
            }
            if (data == '\n') {
                break;
            }
            bos.write(data);
        }
        byte[] arr = bos.toByteArray();
        if (arr.length > 0 && arr[arr.length - 1] == '\r') {
            return new String(arr, 0, arr.length - 1, SentinelConfig.charset());
        }
        return new String(arr, SentinelConfig.charset());
    }

    /**
     * Try to process the body of POST request additionally.
     */
    protected static void processPostRequest(InputStream in, CommandRequest request)
            throws RequestException, IOException {
        Map<String, String> headerMap = parsePostHeaders(in);

        if (headerMap == null) {
            // illegal request
            CommandCenterLog.warn("Illegal request read: null headerMap");
            throw new RequestException(StatusCode.BAD_REQUEST, "");
        }

        if (headerMap.containsKey("content-type") && !checkContentTypeSupported(headerMap.get("content-type"))) {
            // not supported Content-type
            CommandCenterLog.warn("Request not supported: unsupported Content-Type: " + headerMap.get("content-type"));
            throw new RequestException(StatusCode.UNSUPPORTED_MEDIA_TYPE,
                    "Only form-encoded post request is supported");
        }

        int bodyLength = 0;
        try {
            bodyLength = Integer.parseInt(headerMap.get("content-length"));
        } catch (Exception e) {
        }
        if (bodyLength < 1) {
            // illegal request without Content-length header
            CommandCenterLog.warn("Request not supported: no available Content-Length in headers");
            throw new RequestException(StatusCode.LENGTH_REQUIRED, "No legal Content-Length");
        }

        parseParams(readBody(in, bodyLength), request);
    }

    /**
     * Process header line in request
     *
     * @return return headers in a Map, null for illegal request
     */
    protected static Map<String, String> parsePostHeaders(InputStream in) throws IOException {
        Map<String, String> headerMap = new HashMap<String, String>(4);
        String line;
        while (true) {
            line = readLine(in);
            if (line == null || line.length() == 0) {
                // empty line
                return headerMap;
            }
            int index = line.indexOf(":");
            if (index < 1) {
                // empty value, abandon
                continue;
            }
            String headerName = line.substring(0, index).trim().toLowerCase();
            String headerValue = line.substring(index + 1).trim();
            if (headerValue.length() > 0) {
                headerMap.put(headerName, headerValue);
            }
        }
    }

    private static boolean checkContentTypeSupported(String contentType) {
        int idx = contentType.indexOf(";");
        String type;
        if (idx > 0) {
            type = contentType.substring(0, idx).toLowerCase().trim();
        } else {
            type = contentType.toLowerCase();
        }
        // Actually in RFC "x-*" shouldn't have any properties like "type/subtype; key=val"
        // But some library do add it. So we will be compatible with that but force to
        // encoding specified in configuration as legacy processing will do.
        if (!type.contains("application/x-www-form-urlencoded")) {
            // Not supported request type
            // Now simple-http only support form-encoded post request.
            return false;
        }
        return true;
    }

    private static String readBody(InputStream in, int bodyLength)
            throws IOException, RequestException {
        byte[] buf = new byte[bodyLength];
        int pos = 0;
        while (pos < bodyLength) {
            int l = in.read(buf, pos, Math.min(512, bodyLength - pos));
            if (l < 0) {
                break;
            }
            if (l == 0) {
                continue;
            }
            pos += l;
        }
        // Only allow partial
        return new String(buf, 0, pos, SentinelConfig.charset());
    }

    /**
     * Consume all the body submitted and parse params into {@link CommandRequest}
     */
    protected static void parseParams(String queryString, CommandRequest request) {
        if (queryString == null || queryString.length() < 1) {
            return;
        }

        int offset = 0, pos = -1;

        // check anchor
        queryString = removeAnchor(queryString);

        while (true) {
            offset = pos + 1;
            pos = queryString.indexOf('&', offset);
            if (offset == pos) {
                // empty
                continue;
            }
            parseSingleParam(queryString.substring(offset, pos == -1 ? queryString.length() : pos), request);

            if (pos < 0) {
                // reach the end
                break;
            }
        }
    }

    private void closeResource(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                CommandCenterLog.warn("[SimpleHttpCommandCenter] Close resource failed", e);
            }
        }
    }

    private <T> void handleResponse(CommandResponse<T> response, final PrintWriter printWriter) throws Exception {
        if (response.isSuccess()) {
            if (response.getResult() == null) {
                writeResponse(printWriter, StatusCode.OK, null);
                return;
            }
            // Here we directly use `toString` to encode the result to plain text.
            byte[] buffer = response.getResult().toString().getBytes(SentinelConfig.charset());
            writeResponse(printWriter, StatusCode.OK, new String(buffer));
        } else {
            String msg = SERVER_ERROR_MESSAGE;
            if (response.getException() != null) {
                msg = response.getException().getMessage();
            }
            writeResponse(printWriter, StatusCode.BAD_REQUEST, msg);
        }
    }

    private void writeResponse(PrintWriter out, StatusCode statusCode, String message) {
        if (message != null) {
            out.print(message);
        }
        out.flush();
        writtenHead = true;
    }

    /**
     * Parse raw HTTP request line to a {@link CommandRequest}.
     *
     * @return parsed command request
     */
    protected static CommandRequest processQueryString(HttpServletRequest servletRequest) {
        CommandRequest request = new CommandRequest();
        String requestURI = servletRequest.getRequestURI();
        String target = requestURI.substring("/sentinel/".length());
        request.addMetadata(HttpCommandUtils.REQUEST_TARGET, target);
        Map<String, String[]> parameterMap = servletRequest.getParameterMap();
        if (parameterMap != null && !parameterMap.isEmpty()) {
            for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                request.addParam(entry.getKey(), entry.getValue()[0]);
            }
        }
        return request;
    }

    /**
     * Truncate query from "a=1&b=2#mark" to "a=1&b=2"
     */
    protected static String removeAnchor(String str) {
        if (str == null || str.length() == 0) {
            return str;
        }

        int anchor = str.indexOf('#');

        if (anchor == 0) {
            return "";
        } else if (anchor > 0) {
            return str.substring(0, anchor);
        }

        return str;
    }

    protected static void parseSingleParam(String single, CommandRequest request) {
        if (single == null || single.length() < 3) {
            return;
        }

        int index = single.indexOf('=');
        if (index <= 0 || index >= single.length() - 1) {
            // empty key/val or nothing found
            return;
        }

        String value = StringUtil.trim(single.substring(index + 1));
        String key = StringUtil.trim(single.substring(0, index));
        try {
            key = URLDecoder.decode(key, SentinelConfig.charset());
            value = URLDecoder.decode(value, SentinelConfig.charset());
        } catch (UnsupportedEncodingException e) {
        }

        request.addParam(key, value);
    }

}
