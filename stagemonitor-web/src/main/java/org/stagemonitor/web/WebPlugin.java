package org.stagemonitor.web;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.http.HttpServletRequest;

import com.codahale.metrics.MetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stagemonitor.core.CorePlugin;
import org.stagemonitor.core.Stagemonitor;
import org.stagemonitor.core.StagemonitorPlugin;
import org.stagemonitor.core.configuration.Configuration;
import org.stagemonitor.core.configuration.ConfigurationOption;
import org.stagemonitor.core.configuration.converter.SetValueConverter;
import org.stagemonitor.core.elasticsearch.ElasticsearchClient;
import org.stagemonitor.core.instrument.MainStagemonitorClassFileTransformer;
import org.stagemonitor.core.util.StringUtils;
import org.stagemonitor.web.configuration.ConfigurationServlet;
import org.stagemonitor.web.logging.MDCListener;
import org.stagemonitor.web.metrics.StagemonitorMetricsServlet;
import org.stagemonitor.web.monitor.MonitoredHttpRequest;
import org.stagemonitor.web.monitor.filter.HttpRequestMonitorFilter;
import org.stagemonitor.web.monitor.filter.StagemonitorSecurityFilter;
import org.stagemonitor.web.monitor.rum.RumServlet;
import org.stagemonitor.web.monitor.servlet.FileServlet;
import org.stagemonitor.web.monitor.spring.SpringMonitoredHttpRequest;
import org.stagemonitor.web.monitor.widget.RequestTraceServlet;
import org.stagemonitor.web.session.SessionCounter;

import static org.stagemonitor.core.pool.MBeanPooledResource.tomcatThreadPools;
import static org.stagemonitor.core.pool.PooledResourceMetricsRegisterer.registerPooledResources;

public class WebPlugin extends StagemonitorPlugin implements ServletContainerInitializer {

	public static final String STAGEMONITOR_SHOW_WIDGET = "X-Stagemonitor-Show-Widget";

	private static final String WEB_PLUGIN = "Web Plugin";

	private static final Logger logger = LoggerFactory.getLogger(WebPlugin.class);

	static  {
		try {
			final CorePlugin configuration = Stagemonitor.getConfiguration(CorePlugin.class);
			if (configuration.isStagemonitorActive() && configuration.isAttachAgentAtRuntime()) {
				MainStagemonitorClassFileTransformer.performRuntimeAttachment();
			}
		} catch (Exception e) {
			logger.warn(e.getMessage(), e);
		}
	}

	private final ConfigurationOption<Collection<Pattern>> requestParamsConfidential = ConfigurationOption.regexListOption()
			.key("stagemonitor.requestmonitor.http.requestparams.confidential.regex")
			.dynamic(true)
			.label("Confidential request parameters (regex)")
			.description("A list of request parameter name patterns that should not be collected.\n" +
					"A request parameter is either a query string or a application/x-www-form-urlencoded request " +
					"body (POST form content)")
			.defaultValue(Arrays.asList(
					Pattern.compile("(?i).*pass.*"),
					Pattern.compile("(?i).*credit.*"),
					Pattern.compile("(?i).*pwd.*")))
			.tags("security-relevant")
			.configurationCategory(WEB_PLUGIN)
			.build();
	private ConfigurationOption<Boolean> collectHttpHeaders = ConfigurationOption.booleanOption()
			.key("stagemonitor.requestmonitor.http.collectHeaders")
			.dynamic(true)
			.label("Collect HTTP headers")
			.description("Whether or not HTTP headers should be collected with a call stack.")
			.defaultValue(true)
			.configurationCategory(WEB_PLUGIN)
			.tags("security-relevant")
			.build();
	private ConfigurationOption<Boolean> parseUserAgent = ConfigurationOption.booleanOption()
			.key("stagemonitor.requestmonitor.http.parseUserAgent")
			.dynamic(true)
			.label("Analyze user agent")
			.description("Whether or not the user-agent header should be parsed and analyzed to get information " +
					"about the browser, device type and operating system.")
			.defaultValue(true)
			.configurationCategory(WEB_PLUGIN)
			.build();
	private ConfigurationOption<Collection<String>> excludeHeaders = ConfigurationOption.lowerStringsOption()
			.key("stagemonitor.requestmonitor.http.headers.excluded")
			.dynamic(true)
			.label("Do not collect headers")
			.description("A list of (case insensitive) header names that should not be collected.")
			.defaultValue(new LinkedHashSet<String>(Arrays.asList("cookie", "authorization", STAGEMONITOR_SHOW_WIDGET)))
			.configurationCategory(WEB_PLUGIN)
			.tags("security-relevant")
			.build();
	private final ConfigurationOption<Boolean> widgetEnabled = ConfigurationOption.booleanOption()
			.key("stagemonitor.web.widget.enabled")
			.dynamic(true)
			.label("In browser widget enabled")
			.description("If active, stagemonitor will inject a widget in the web site containing the calltrace " +
					"metrics.\n" +
					"Requires Servlet-Api >= 3.0")
			.defaultValue(true)
			.configurationCategory(WEB_PLUGIN)
			.build();
	private final ConfigurationOption<Map<Pattern, String>> groupUrls = ConfigurationOption.regexMapOption()
			.key("stagemonitor.groupUrls")
			.dynamic(true)
			.label("Group URLs regex")
			.description("Combine url paths by regex to a single url group.\n" +
					"E.g. `(.*).js: *.js` combines all URLs that end with `.js` to a group named `*.js`. " +
					"The metrics for all URLs matching the pattern are consolidated and shown in one row in the request table. " +
					"The syntax is `<regex>: <group name>[, <regex>: <group name>]*`")
			.defaultValue(
					new LinkedHashMap<Pattern, String>() {{
						put(Pattern.compile("(.*).js$"), "*.js");
						put(Pattern.compile("(.*).css$"), "*.css");
						put(Pattern.compile("(.*).jpg$"), "*.jpg");
						put(Pattern.compile("(.*).jpeg$"), "*.jpeg");
						put(Pattern.compile("(.*).png$"), "*.png");
					}})
			.configurationCategory(WEB_PLUGIN)
			.build();
	private final ConfigurationOption<Boolean> rumEnabled = ConfigurationOption.booleanOption()
			.key("stagemonitor.web.rum.enabled")
			.dynamic(true)
			.label("Enable Real User Monitoring")
			.description("The Real User Monitoring feature collects the browser, network and overall percieved " +
					"execution time from the user's perspective. When activated, a piece of javascript will be " +
					"injected to each html page that collects the data from real users and sends it back " +
					"to the server. Servlet API 3.0 or higher is required for this.")
			.defaultValue(true)
			.configurationCategory(WEB_PLUGIN)
			.build();
	private final ConfigurationOption<Boolean> collectPageLoadTimesPerRequest = ConfigurationOption.booleanOption()
			.key("stagemonitor.web.collectPageLoadTimesPerRequest")
			.dynamic(true)
			.label("Collect Page Load Time data per request group")
			.description("Whether or not browser, network and overall execution time should be collected per request group.\n" +
					"If set to true, four additional timers will be created for each request group to record the page " +
					"rendering time, dom processing time, network time and overall time per request. " +
					"If set to false, the times of all requests will be aggregated.")
			.defaultValue(false)
			.configurationCategory(WEB_PLUGIN)
			.build();
	private final ConfigurationOption<Collection<String>> excludedRequestPaths = ConfigurationOption.stringsOption()
			.key("stagemonitor.web.paths.excluded")
			.dynamic(false)
			.label("Excluded paths")
			.description("Request paths that should not be monitored. " +
					"A value of `/aaa` means, that all paths starting with `/aaa` should not be monitored." +
					" It's recommended to not monitor static resources, as they are typically not interesting to " +
					"monitor but consume resources when you do.")
			.defaultValue(SetValueConverter.immutableSet(
					// exclude paths of static vaadin resources
					"/VAADIN/",
					// don't monitor vaadin heatbeat
					"/HEARTBEAT/"))
			.configurationCategory(WEB_PLUGIN)
			.build();
	private final ConfigurationOption<Boolean> monitorOnlyForwardedRequests = ConfigurationOption.booleanOption()
			.key("stagemonitor.web.monitorOnlyForwardedRequests")
			.dynamic(true)
			.label("Monitor only forwarded requests")
			.description("Sometimes you only want to monitor forwarded requests, for example if you have a rewrite " +
					"filter that translates a external URI (/a) to a internal URI (/b). If only /b should be monitored," +
					"set the value to true.")
			.defaultValue(false)
			.configurationCategory(WEB_PLUGIN)
			.build();
	private final ConfigurationOption<String> metricsServletAllowedOrigin = ConfigurationOption.stringOption()
			.key("stagemonitor.web.metricsServlet.allowedOrigin")
			.dynamic(true)
			.label("Allowed origin")
			.description("The Access-Control-Allow-Origin header value for the metrics servlet.")
			.defaultValue(null)
			.configurationCategory(WEB_PLUGIN)
			.build();
	private final ConfigurationOption<String> metricsServletJsonpParameter = ConfigurationOption.stringOption()
			.key("stagemonitor.web.metricsServlet.jsonpParameter")
			.dynamic(true)
			.label("The Jsonp callback parameter name")
			.description("The name of the parameter used to specify the jsonp callback.")
			.defaultValue(null)
			.configurationCategory(WEB_PLUGIN)
			.build();
	private ConfigurationOption<Boolean> onlyMvcOption = ConfigurationOption.booleanOption()
			.key("stagemonitor.requestmonitor.spring.monitorOnlySpringMvcRequests")
			.dynamic(true)
			.label("Monitor only SpringMVC requests")
			.description("Whether or not requests should be ignored, if they will not be handled by a Spring MVC controller method.\n" +
					"This is handy, if you are not interested in the performance of serving static files. " +
					"Setting this to true can also significantly reduce the amount of files (and thus storing space) " +
					"Graphite will allocate.")
			.defaultValue(false)
			.configurationCategory("Spring MVC Plugin")
			.build();

	@Override
	public void initializePlugin(MetricRegistry registry, Configuration config) {
		registerPooledResources(registry, tomcatThreadPools());
		ElasticsearchClient elasticsearchClient = config.getConfig(CorePlugin.class).getElasticsearchClient();
		elasticsearchClient.sendGrafanaDashboardAsync("Server.json");
		elasticsearchClient.sendGrafanaDashboardAsync("KPIs over Time.json");
	}

	@Override
	public List<ConfigurationOption<?>> getConfigurationOptions() {
		final List<ConfigurationOption<?>> configurationOptions = super.getConfigurationOptions();
		try {
			Class.forName("org.springframework.web.servlet.HandlerMapping");
		} catch (ClassNotFoundException e) {
			configurationOptions.remove(onlyMvcOption);
		}

		return configurationOptions;
	}

	public boolean isCollectHttpHeaders() {
		return collectHttpHeaders.getValue();
	}

	public boolean isParseUserAgent() {
		return parseUserAgent.getValue();
	}

	public Collection<String> getExcludeHeaders() {
		return excludeHeaders.getValue();
	}

	public boolean isWidgetEnabled() {
		return widgetEnabled.getValue();
	}

	public Map<Pattern, String> getGroupUrls() {
		return groupUrls.getValue();
	}

	public Collection<Pattern> getRequestParamsConfidential() {
		return requestParamsConfidential.getValue();
	}

	public boolean isRealUserMonitoringEnabled() {
		return rumEnabled.getValue();
	}

	public boolean isCollectPageLoadTimesPerRequest() {
		return collectPageLoadTimesPerRequest.getValue();
	}

	public Collection<String> getExcludedRequestPaths() {
		return excludedRequestPaths.getValue();
	}

	public boolean isMonitorOnlyForwardedRequests() {
		return monitorOnlyForwardedRequests.getValue();
	}

	public String getMetricsServletAllowedOrigin() {
		return metricsServletAllowedOrigin.getValue();
	}

	public String getMetricsServletJsonpParamName() {
		return metricsServletJsonpParameter.getValue();
	}

	public boolean isWidgetAndStagemonitorEndpointsAllowed(HttpServletRequest request, Configuration configuration) {
		if (request.getAttribute(STAGEMONITOR_SHOW_WIDGET) != null) {
			return (Boolean) request.getAttribute(STAGEMONITOR_SHOW_WIDGET);
		}

		return isWidgetEnabled() || isPasswordInShowWidgetHeaderCorrect(request, configuration);
	}

	private boolean isPasswordInShowWidgetHeaderCorrect(HttpServletRequest request, Configuration configuration) {
		String password = request.getHeader(STAGEMONITOR_SHOW_WIDGET);
		try {
			configuration.assertPasswordCorrect(password);
			return true;
		} catch (IllegalStateException e) {
			if (StringUtils.isNotEmpty(password)) {
				logger.error("{} This might be a malicious attempt to guess the password of the {} header. The request was initiated from the ip {}.", e.getMessage(), STAGEMONITOR_SHOW_WIDGET, MonitoredHttpRequest.getClientIp(request));
			}
			return false;
		}
	}

	public boolean isMonitorOnlySpringMvcRequests() {
		return onlyMvcOption.getValue();
	}

	@Override
	public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException {
		ctx.addServlet(ConfigurationServlet.class.getSimpleName(), new ConfigurationServlet())
				.addMapping(ConfigurationServlet.CONFIGURATION_ENDPOINT);
		ctx.addServlet(StagemonitorMetricsServlet.class.getSimpleName(), new StagemonitorMetricsServlet())
				.addMapping("/stagemonitor/metrics");
		ctx.addServlet(RumServlet.class.getSimpleName(), new RumServlet())
				.addMapping("/stagemonitor/public/rum");
		ctx.addServlet(FileServlet.class.getSimpleName(), new FileServlet())
				.addMapping("/stagemonitor/static/*", "/stagemonitor/public/static/*");

		final ServletRegistration.Dynamic requestTraceServlet = ctx.addServlet(RequestTraceServlet.class.getSimpleName(), new RequestTraceServlet());
		requestTraceServlet.addMapping("/stagemonitor/request-traces");
		requestTraceServlet.setAsyncSupported(true);


		final FilterRegistration.Dynamic securityFilter = ctx.addFilter(StagemonitorSecurityFilter.class.getSimpleName(), new StagemonitorSecurityFilter());
		securityFilter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/stagemonitor/*");
		securityFilter.setAsyncSupported(true);

		final FilterRegistration.Dynamic monitorFilter = ctx.addFilter(HttpRequestMonitorFilter.class.getSimpleName(), new HttpRequestMonitorFilter());
		monitorFilter.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD), false, "/*");
		monitorFilter.setAsyncSupported(true);


		ctx.addListener(MDCListener.class);
		ctx.addListener(MonitoredHttpRequest.StagemonitorServletContextListener.class);
		ctx.addListener(SpringMonitoredHttpRequest.HandlerMappingServletContextListener.class);
		ctx.addListener(SessionCounter.class);
	}
}
