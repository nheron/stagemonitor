package org.stagemonitor.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpClient {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public int sendAsJson(final String method, final String url, final Object requestBody) {
		return sendAsJson(method, url, requestBody, null);
	}

	public int sendAsJson(final String method, final String url, final Object requestBody, final Map<String, String> headerFields) {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setDoOutput(true);
			connection.setRequestMethod(method);
			if (headerFields != null) {
				for (Map.Entry<String, String> header : headerFields.entrySet()) {
					connection.setRequestProperty(header.getKey(), header.getValue());
				}
			}

			writeRequestBody(requestBody, connection);

			IOUtils.consumeAndClose(connection.getInputStream());

			return connection.getResponseCode();
		} catch (IOException e) {
			if (connection != null) {
				logger.warn(e.getMessage() + ": " + getErrorMessage(connection));
				return getResponseCode(connection, e);
			} else {
				return -1;
			}
		}
	}

	private int getResponseCode(HttpURLConnection connection, IOException e) {
		try {
			return connection.getResponseCode();
		} catch (IOException e1) {
			logger.warn(e.getMessage());
			return -1;
		}
	}

	private String getErrorMessage(HttpURLConnection connection) {
		InputStream errorStream = null;
		try {
			errorStream = connection.getErrorStream();
			return IOUtils.toString(errorStream);
		} catch (IOException e) {
			return e.getMessage();
		} finally {
			IOUtils.closeQuietly(errorStream);
		}
	}

	private void writeRequestBody(Object requestBody, HttpURLConnection connection) throws IOException {
		if (requestBody != null) {
			connection.setRequestProperty("Content-Type", "application/json");
			OutputStream os = connection.getOutputStream();

			if (requestBody instanceof InputStream) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = ((InputStream) requestBody).read(buf)) > 0) {
					os.write(buf, 0, n);
				}
			} else {
				JsonUtils.writeJsonToOutputStream(requestBody, os);
			}
			os.flush();
		}
	}
}
