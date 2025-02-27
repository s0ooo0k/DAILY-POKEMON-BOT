import javax.crypto.ShortBufferException;
import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class App {
    public static void main(String[] args) {
        Monitoring monitoring = new Monitoring();
        GetKeyword key = new GetKeyword();
        GetLucky lucky = new GetLucky();
        String keyword = key.useLLM(System.getenv("PROMPT"));

        // llm 뒤에 개행문자 출력 제거
        String setKeyword = keyword.replace("\\n", "").trim();
        System.out.println(" setKeyword = " + setKeyword);

        Map<String, String> searchResult = monitoring.getShop(setKeyword, 3, 1, SortType.date);
        String imageLink = searchResult.get("imageLink");

        // slack 의 하이퍼링크 결과처럼 변경
        StringBuilder newText = new StringBuilder();
        for (Map.Entry<String, String> entry : searchResult.entrySet()) {
            if (!entry.getKey().equals("imageLink")) { // ✅ 이미지 링크 제외
                newText.append(String.format("<%s|%s>\n", entry.getValue(), entry.getKey()));
            }
        }

        String luckyMsg = lucky.useLLM(setKeyword);


        String jsonPayload = String.format("""
                {
                    "attachments":[
                        {
                            "fallback": "😎 오늘의 포-켓몬 🎵",
                            "color": "#add8e6",
                            "image_url": "%s",
                            "fields": [
                             {
                                  "title": "😎 오늘의 포-켓몬은 %s 🎵",
                                  "value": "%s. \n 포켓몬에 대한 더 많은 정보는 \n %s",
                                  "short": false
                             }
                          ]
                        }
                   ]
                }
                """, imageLink, setKeyword, luckyMsg, newText.toString());

        SlackBot.sendSlackMessage(jsonPayload);


    }
}

// 정렬 방식 지정하는 열거형
enum SortType {
    sim("sim"), date("date");
    // 관련도 순 정렬, 날짜 순 정렬
    final String value;

    SortType(String value) {
        this.value = value;
    }
}

// ====================================== 검색 API 호출 =========================================
class Monitoring {
    private final Logger logger;

    public Monitoring() {
        logger = Logger.getLogger(Monitoring.class.getName());
        logger.setLevel(Level.SEVERE);
        logger.info("Pokemon.Monitoring 객체 생성");
    }

    // ========================= 검색어를 통해서 최근 5개의 웹문서 정보 받기 ====================================
    public Map<String, String> getShop(String keyword, int display, int start, SortType sort) {

        // title-link를 연결하기 위해 Map 사용
        Map<String, String> shopMap = new LinkedHashMap<>();
        String folderPath = "pokemon_data";

        File folder = new File(folderPath);
        if(!folder.exists()){
            folder.mkdir();
        }

        String imageLink = "";
        try {
            // 하단 데이터 메서드 호출해서 json 형태로 가져옴
            String response = getDataFromAPI("webkr.json", keyword, display, start, sort);
            String[] tmp = response.split("title\":\"");


            // 0번째를 제외하곤 데이터 tmp[0]에는 title 이전의 json 데이터
            for (int i = 1; i < tmp.length; i++) {
                String title = tmp[i].split("\",")[0].replaceAll("<[^>]+>", "");;
                String link = tmp[i].split("\"link\":\"")[1].split("\",")[0];

                shopMap.put(title, link);
            }


            File file = new File(folderPath, "%d_%s.txt".formatted(new Date().getTime(), keyword));
            if (!file.exists()) {
                logger.info(file.createNewFile() ? "신규 생성" : "이미 있음");
            }
            try (FileWriter fileWriter = new FileWriter(file)) {
                for (Map.Entry<String, String> entry : shopMap.entrySet()) {
                    fileWriter.write(entry.getKey() + " | " + entry.getValue() + "\n");
                }
                logger.info("기록 성공");
            }

            logger.info("제목 목록 생성 완료");

            // ================================== 이미지 =====================================
            String imageResponse = getDataFromAPI("image", keyword, display, start, SortType.sim);
            imageLink = imageResponse
                    .split("\"link\":\"")[1].split("\",")[0]  // "link": 이후 값 추출
                    .replace("\\/", "/")  // \/ → / 로 변경
                    .split("\\?")[0]; // URL 뒤에 ? 파라미터 제거
            logger.info(imageLink);
            shopMap.put("imageLink", imageLink);

            // 한글, 특수문자가 포함된 경우 안전한 파일명으로 변경
            String safeKeyword = keyword.replaceAll("[^a-zA-Z0-9가-힣]", "_"); // 특수문자 제거
            String safeExt = imageLink.substring(imageLink.lastIndexOf(".") + 1); // 확장자 추출

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageLink))
                    .build();
            String[] tmp2 = imageLink.split("\\.");

            Path path = Path.of(folderPath, "%d_%s.%s".formatted(
                    new Date().getTime(), keyword, tmp2[tmp2.length - 1]));

            HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofFile(path));

        } catch (Exception e) {
            logger.severe(e.getMessage());
        }
        return shopMap;
    }

    // ================================= 검색 메서드 ==================================================
    private String getDataFromAPI(String path, String keyword, int display, int start, SortType sort) throws Exception {
        // 캐싱

        // API 요청 URL 형식  GET /v1/search/shop.xml?query=%EC%A3%BC%EC%8B%9D&display=10&start=1&sort=sim HTTP/1.1
        String url = "https://openapi.naver.com/v1/search/%s".formatted(path);
        String params = "query=%s&display=%d&start=%d&sort=%s".formatted(
                keyword, display, start, sort.value
        );

        HttpClient client = HttpClient.newHttpClient(); // 클라이언트
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "?" + params))
                .GET()
                .header("X-Naver-Client-Id", System.getenv("NAVER_CLIENT_ID"))
                .header("X-Naver-Client-Secret", System.getenv("NAVER_CLIENT_SECRET"))
                .build();

        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            // http 요청을 했을 때 잘 왔는지 보는 것
            logger.info(Integer.toString(response.statusCode()));
            logger.info(response.body());
            // split하든 나중에 GSON, Jackson
            return response.body();
        } catch (Exception e) {
            logger.severe(e.getMessage());
            throw new Exception("연결 에러");
        }
    }
}


// ======================= 오늘의 포켓몬 받기 =========================================

class GetKeyword {
    public static String useLLM(String prompt) {
        String apiKey = System.getenv("LLM_API_KEY"); // 환경변수로 관리
        String apiUrl = System.getenv("LLM_API_URL"); // 환경변수로 관리

        if (!apiUrl.contains("?key=")) {
            apiUrl += "?key=" + apiKey;
        }
        String payload = String.format("""
                {
                    "contents": [
                        {
                            "role": "user",
                            "parts": [
                                {
                                    "text": "%s"
                                }
                            ]
                        }
                    ],
                    "generationConfig": {
                                "stopSequences": [
                                    "Title"
                                ],
                                "temperature": 2.0,
                                "maxOutputTokens": 800,
                                "topP": 0.8,
                                "topK": 30
                    }
                }
                """, prompt);

        HttpClient client = HttpClient.newHttpClient(); // 새롭게 요청할 클라이언트 생성
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl)) // URL을 통해서 어디로 요청을 보내는지 결정
                .header("Content-Type", "application/json")
                //.header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(); // 핵심
        try { // try
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();
            String result = null;
            // content 값이 시작하는 위치

            // ============= Gemini 문자열 파싱 ================ //
            String patternString = "\"text\":\\s*\"([^\"]+)\"";
            Pattern pattern = Pattern.compile(patternString);
            Matcher matcher = pattern.matcher(responseBody);

            if (matcher.find()) {
                return matcher.group(1).trim(); // ✅ 찾은 값 반환 (앞뒤 공백 제거)
            } else {
                System.out.println("'text' 값을 찾을 수 없음!");
                return "⚠ API 응답에서 'text' 값을 찾을 수 없음!";
            }

        } catch (Exception e) { // 예외 처리
            throw new RuntimeException(e);
        }
    }
}

// ================ 오늘의 메세지 =======================

class GetLucky {
    public static String useLLM(String setKeyword) {
        String apiKey = System.getenv("LLM_API_KEY"); // 환경변수로 관리
        String apiUrl = System.getenv("LLM_API_URL"); // 환경변수로 관리

        String luckyPrmopt = String.format(System.getenv("LUCKY_PROMPT"), setKeyword);
        if (!apiUrl.contains("?key=")) {
            apiUrl += "?key=" + apiKey;
        }
        String payload = String.format("""
                {
                    "contents": [
                        {
                            "role": "user",
                            "parts": [
                                {
                                    "text": "%s"
                                }
                            ]
                        }
                    ],
                }
                """, luckyPrmopt);

        HttpClient client = HttpClient.newHttpClient(); // 새롭게 요청할 클라이언트 생성
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl)) // URL을 통해서 어디로 요청을 보내는지 결정
                .header("Content-Type", "application/json")
                //.header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(); // 핵심
        try { // try
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String responseBody = response.body();
            String result = null;
            // content 값이 시작하는 위치

            // ============= Gemini 문자열 파싱 ================ //
            String patternString = "\"text\":\\s*\"([^\"]+)\"";
            Pattern pattern = Pattern.compile(patternString);
            Matcher matcher = pattern.matcher(responseBody);

            if (matcher.find()) {
                return matcher.group(1).trim(); // ✅ 찾은 값 반환 (앞뒤 공백 제거)
            } else {
                System.out.println("'text' 값을 찾을 수 없음!");
                return "⚠ API 응답에서 'text' 값을 찾을 수 없음!";
            }

        } catch (Exception e) { // 예외 처리
            throw new RuntimeException(e);
        }
    }
}




// =========================== 슬랙 봇 연결 ============================= //
class SlackBot {
    public static void sendSlackMessage(String jsonPayload) {
        String slackUrl = System.getenv("SLACK_WEBHOOK_URL");

        // 브라우저나 유저인 척하는 것
        HttpClient client = HttpClient.newHttpClient();
        // 요청을 만들어보자 ! (fetch)
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(slackUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        // 네트워크 과정에서 오류가 있을 수 있기에 선제적으로 예외처리가 필요
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("response.statusCode() = " + response.statusCode());
            System.out.println("response.body = " + response.body());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

