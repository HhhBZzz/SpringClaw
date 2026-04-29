package com.springclaw.tool.pack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 天气查询工具包（免 API Key 版本）。
 */
@Component
@SuppressWarnings("unchecked")
public class WeatherToolPack {

    private final boolean enabled;
    private final String urlTemplate;
    private final String cnUrlTemplate;
    private final int requestTimeoutSeconds;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, String> weatherCache;

    public WeatherToolPack(boolean enabled,
                           String urlTemplate,
                           int timeoutSeconds,
                           String cnUrlTemplate) {
        this(enabled, urlTemplate, timeoutSeconds, cnUrlTemplate, defaultCache());
    }

    // 保留兼容构造器，避免测试夹具和旧调用点因为移除 web 抓取兜底而全部重写。
    public WeatherToolPack(boolean enabled,
                           String urlTemplate,
                           int timeoutSeconds,
                           String cnUrlTemplate,
                           WebSearchToolPack ignoredWebSearchToolPack) {
        this(enabled, urlTemplate, timeoutSeconds, cnUrlTemplate);
    }

    @Autowired
    public WeatherToolPack(@Value("${springclaw.tools.weather.enabled:true}") boolean enabled,
                           @Value("${springclaw.tools.weather.url-template:https://wttr.in/{city}?format=j1}") String urlTemplate,
                           @Value("${springclaw.tools.weather.timeout-seconds:8}") int timeoutSeconds,
                           @Value("${springclaw.tools.weather.cn-url-template:https://www.weather.com.cn/data/sk/{cityCode}.html}") String cnUrlTemplate,
                           @Qualifier("weatherCache") Cache<String, String> weatherCache) {
        this.enabled = enabled;
        this.urlTemplate = StringUtils.hasText(urlTemplate)
                ? urlTemplate.trim()
                : "https://wttr.in/{city}?format=j1";
        this.cnUrlTemplate = StringUtils.hasText(cnUrlTemplate)
                ? cnUrlTemplate.trim()
                : "https://www.weather.com.cn/data/sk/{cityCode}.html";
        this.requestTimeoutSeconds = Math.max(1, timeoutSeconds);
        this.httpClient = buildHttpClient(timeoutSeconds);
        this.objectMapper = new ObjectMapper();
        this.weatherCache = weatherCache;
    }

    private static Cache<String, String> defaultCache() {
        return Caffeine.newBuilder()
                .maximumSize(128)
                .build();
    }

    private HttpClient buildHttpClient(int timeoutSeconds) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
    }

    @Tool(description = "查询城市天气（输入城市名，例如 北京、Shanghai）")
    public String queryWeather(String city) {
        if (!enabled) {
            return "天气工具未开启";
        }
        if (!StringUtils.hasText(city)) {
            return "请输入城市名";
        }
        String normalizedCity = city.trim();
        String cacheKey = normalizedCity.toLowerCase();
        String cached = weatherCache.getIfPresent(cacheKey);
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        String encodedCity = URLEncoder.encode(normalizedCity, StandardCharsets.UTF_8);
        List<String> errors = new ArrayList<>();

        Optional<String> openMeteo = tryOpenMeteo(normalizedCity);
        if (openMeteo.isPresent()) {
            weatherCache.put(cacheKey, openMeteo.get());
            return openMeteo.get();
        }
        errors.add("open-meteo");

        Optional<String> cnWeather = tryWeatherComCn(normalizedCity);
        if (cnWeather.isPresent()) {
            weatherCache.put(cacheKey, cnWeather.get());
            return cnWeather.get();
        }
        errors.add("weather.com.cn");

        Optional<String> wttr = tryWttr(normalizedCity, encodedCity);
        if (wttr.isPresent()) {
            weatherCache.put(cacheKey, wttr.get());
            return wttr.get();
        }
        errors.add("wttr.in");

        return "天气查询失败：已尝试 " + String.join("、", errors) + "，但都未返回有效结果";
    }

    private Optional<String> tryWeatherComCn(String city) {
        String cityCode = resolveCnCityCode(city);
        if (!StringUtils.hasText(cityCode)) {
            return Optional.empty();
        }
        String url = cnUrlTemplate.replace("{cityCode}", cityCode);
        try {
            String body = fetchText(url);
            if (!StringUtils.hasText(body)) {
                return Optional.empty();
            }
            String normalizedBody = normalizeWeatherComBody(body);
            Map<String, Object> data = objectMapper.readValue(normalizedBody, new TypeReference<>() {
            });
            if (data == null || data.isEmpty()) {
                return Optional.empty();
            }
            Object weatherInfoObj = data.get("weatherinfo");
            if (!(weatherInfoObj instanceof Map<?, ?> weatherInfo)) {
                return Optional.empty();
            }
            String cityName = readField(weatherInfo, "city", city);
            String temp = readField(weatherInfo, "temp", "N/A");
            String windDirection = readField(weatherInfo, "WD", "N/A");
            String windPower = readField(weatherInfo, "WS", "N/A");
            String humidity = readField(weatherInfo, "SD", "N/A");
            String observeTime = readField(weatherInfo, "time", "N/A");
            String rain = readField(weatherInfo, "rain", "N/A");
            if (!StringUtils.hasText(temp) || "N/A".equalsIgnoreCase(temp)) {
                return Optional.empty();
            }
            return Optional.of("城市: " + cityName
                    + "\n来源: weather.com.cn"
                    + "\n温度: " + temp + "℃"
                    + "\n湿度: " + humidity
                    + "\n风向: " + windDirection
                    + "\n风力: " + windPower
                    + "\n降雨: " + rain
                    + "\n观测时间: " + observeTime);
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    private String normalizeWeatherComBody(String body) {
        if (!StringUtils.hasText(body)) {
            return body;
        }
        // weather.com.cn 返回 text/html 且常缺少 charset，某些 HTTP 客户端会按 ISO-8859-1 解码。
        // 这里做一次兼容修正，避免中文字段乱码。
        if (body.contains("å") || body.contains("ä")) {
            try {
                return new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            } catch (Exception ignore) {
                return body;
            }
        }
        return body;
    }

    private String readField(Map<?, ?> source, String key, String defaultValue) {
        if (source == null) {
            return defaultValue;
        }
        Object value = source.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private Optional<String> tryWttr(String city, String encodedCity) {
        String url = urlTemplate.replace("{city}", encodedCity);
        try {
            Map<String, Object> data = fetchJson(url);
            if (data == null) {
                return Optional.empty();
            }
            List<Map<String, Object>> current = (List<Map<String, Object>>) data.get("current_condition");
            if (current == null || current.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> item = current.get(0);
            String temp = String.valueOf(item.getOrDefault("temp_C", "N/A"));
            String feels = String.valueOf(item.getOrDefault("FeelsLikeC", "N/A"));
            String humidity = String.valueOf(item.getOrDefault("humidity", "N/A"));
            String weather = "";
            Object weatherDescObj = item.get("weatherDesc");
            if (weatherDescObj instanceof List<?> weatherDescList && !weatherDescList.isEmpty()) {
                Object first = weatherDescList.get(0);
                if (first instanceof Map<?, ?> map) {
                    Object value = map.get("value");
                    weather = value == null ? "" : String.valueOf(value);
                }
            }
            return Optional.of("城市: " + city
                    + "\n来源: wttr.in"
                    + "\n天气: " + (StringUtils.hasText(weather) ? weather : "未知")
                    + "\n温度: " + temp + "℃"
                    + "\n体感: " + feels + "℃"
                    + "\n湿度: " + humidity + "%");
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    private Optional<String> tryOpenMeteo(String city) {
        try {
            String query = mapCityForMeteo(city);
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=1&language=zh&format=json";
            Map<String, Object> geoData = fetchJson(geoUrl);
            if (geoData == null) {
                return Optional.empty();
            }
            List<Map<String, Object>> results = (List<Map<String, Object>>) geoData.get("results");
            if (results == null || results.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> first = results.get(0);
            Object lat = first.get("latitude");
            Object lon = first.get("longitude");
            if (lat == null || lon == null) {
                return Optional.empty();
            }
            String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon
                    + "&current=temperature_2m,relative_humidity_2m,weather_code&timezone=Asia%2FShanghai";
            Map<String, Object> weatherData = fetchJson(weatherUrl);
            if (weatherData == null) {
                return Optional.empty();
            }
            Map<String, Object> current = (Map<String, Object>) weatherData.get("current");
            if (current == null || current.isEmpty()) {
                return Optional.empty();
            }
            String weather = weatherCodeToText(current.get("weather_code"));
            return Optional.of("城市: " + city
                    + "\n来源: open-meteo"
                    + "\n观测时间: " + current.getOrDefault("time", "N/A")
                    + "\n天气: " + weather
                    + "\n温度: " + current.getOrDefault("temperature_2m", "N/A") + "℃"
                    + "\n湿度: " + current.getOrDefault("relative_humidity_2m", "N/A") + "%");
        } catch (Exception ignore) {
            return Optional.empty();
        }
    }

    private String fetchText(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Accept", "application/json,text/plain,*/*")
                .header("User-Agent", "SpringClaw-Java/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("http status=" + response.statusCode());
        }
        return response.body();
    }

    private Map<String, Object> fetchJson(String url) throws Exception {
        String body = fetchText(url);
        if (!StringUtils.hasText(body)) {
            return Map.of();
        }
        return objectMapper.readValue(body, new TypeReference<>() {
        });
    }

    private String resolveCnCityCode(String city) {
        if (!StringUtils.hasText(city)) {
            return "";
        }
        String trimmed = city.trim();
        if (trimmed.matches("^\\d{9}$")) {
            return trimmed;
        }
        return switch (trimmed) {
            case "北京" -> "101010100";
            case "上海" -> "101020100";
            case "天津" -> "101030100";
            case "重庆" -> "101040100";
            case "广州" -> "101280101";
            case "深圳" -> "101280601";
            case "杭州" -> "101210101";
            case "南京" -> "101190101";
            case "苏州" -> "101190401";
            case "成都" -> "101270101";
            case "武汉" -> "101200101";
            case "西安" -> "101110101";
            case "长沙" -> "101250101";
            case "郑州" -> "101180101";
            case "青岛" -> "101120201";
            case "济南" -> "101120101";
            case "厦门" -> "101230201";
            case "福州" -> "101230101";
            case "合肥" -> "101220101";
            case "南昌" -> "101240101";
            case "沈阳" -> "101070101";
            case "大连" -> "101070201";
            case "哈尔滨" -> "101050101";
            case "长春" -> "101060101";
            case "石家庄" -> "101090101";
            case "太原" -> "101100101";
            case "昆明" -> "101290101";
            case "贵阳" -> "101260101";
            case "南宁" -> "101300101";
            case "海口" -> "101310101";
            case "拉萨" -> "101140101";
            case "乌鲁木齐" -> "101130101";
            case "兰州" -> "101160101";
            case "西宁" -> "101150101";
            case "呼和浩特" -> "101080101";
            case "银川" -> "101170101";
            case "香港" -> "101320101";
            case "澳门" -> "101330101";
            default -> "";
        };
    }

    private String mapCityForMeteo(String city) {
        return switch (city) {
            case "北京" -> "Beijing";
            case "上海" -> "Shanghai";
            case "广州" -> "Guangzhou";
            case "深圳" -> "Shenzhen";
            case "杭州" -> "Hangzhou";
            case "成都" -> "Chengdu";
            case "武汉" -> "Wuhan";
            case "西安" -> "Xi'an";
            case "南京" -> "Nanjing";
            case "苏州" -> "Suzhou";
            case "哈尔滨" -> "Harbin";
            case "天津" -> "Tianjin";
            case "重庆" -> "Chongqing";
            case "长沙" -> "Changsha";
            case "郑州" -> "Zhengzhou";
            case "青岛" -> "Qingdao";
            case "济南" -> "Jinan";
            case "厦门" -> "Xiamen";
            case "福州" -> "Fuzhou";
            case "合肥" -> "Hefei";
            case "南昌" -> "Nanchang";
            case "沈阳" -> "Shenyang";
            case "大连" -> "Dalian";
            case "长春" -> "Changchun";
            case "石家庄" -> "Shijiazhuang";
            case "太原" -> "Taiyuan";
            case "昆明" -> "Kunming";
            case "贵阳" -> "Guiyang";
            case "南宁" -> "Nanning";
            case "海口" -> "Haikou";
            case "拉萨" -> "Lhasa";
            case "乌鲁木齐" -> "Urumqi";
            case "兰州" -> "Lanzhou";
            case "西宁" -> "Xining";
            case "呼和浩特" -> "Hohhot";
            case "银川" -> "Yinchuan";
            case "香港" -> "Hong Kong";
            case "澳门" -> "Macau";
            default -> city;
        };
    }

    private String weatherCodeToText(Object codeObj) {
        if (codeObj == null) {
            return "未知";
        }
        int code;
        try {
            code = Integer.parseInt(String.valueOf(codeObj));
        } catch (Exception ex) {
            return "未知";
        }
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "大致晴朗";
            case 2 -> "局部多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 71, 73, 75 -> "降雪";
            case 80, 81, 82 -> "阵雨";
            case 95, 96, 99 -> "雷暴";
            default -> "天气码=" + code;
        };
    }
}
