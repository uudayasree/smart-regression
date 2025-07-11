package com.example.delivery.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.example.delivery.model.QtestRequirement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class RepoPushEventsProcessor {

	private static final String NEXT_LINE_DELIMETER = "\n";

	private static final String PR_MESSAGE = "message";

	private static final String REFS_HEADS_MAIN = "refs/heads/dev";

	@Autowired
	RestTemplate restTemplate;

	@Value("${test-tool-url}")
	private String zephyreUrl;

	@Value("${qtest-url}")
	private String qtestUrl;

	@Value("${jenkins-url}")
	private String jenkinsUrl;

	@Value("${use-qtest}")
	private boolean useQtest;

	@Value("${projectId}")
	private String projectId;

	@Value("${zephyre-key}")
	private String zephyreKey;

	@Value("${qtest-key}")
	private String qtestKey;

	@Value("${jiraId-pattern}")
	private String jiraIdPatternConfig;
	@Value("${application-healthcheck-url}")
	private String appHealthCheck;

	@Value("${jenkins-userName}")
	private String jenkinsUserName;

	@Value("${jenkins-userPwd}")
	private String jenkinsuserPwd;

	@Value("${jenkins-userAPIToken}")
	private String jenkinsuserAPIToken;

	@Value("${MLModel-url}")
	private String mlModelURL;

	private static final String HEAD_COMMIT = "head_commit";
	private static final String REF = "ref";

	public ResponseEntity<String> processPRRequest(String pushEvent) {

		JsonParser parser = JsonParserFactory.getJsonParser();
		Map<String, Object> req = parser.parseMap(pushEvent);

		if (null != req.get("action") && req.get("action").equals("closed")) {
			String command = getJenkinsBuildUrl("ShoppingCart-Services");
			try {
				Runtime.getRuntime().exec(command);
				return ResponseEntity.ok("Application build triggered Successful ");
			} catch (IOException e) {
				log.error("Error occured while calling the Jnekins build job {}",e.getMessage());
			}
		}
		return ResponseEntity.ok("");
	}

	public ResponseEntity<String> processPushEvent(String pushEvent) {

		ObjectMapper objectMapper = new ObjectMapper();
		List<String> testCaseId = null;
		JsonParser parser = JsonParserFactory.getJsonParser();
		Map<String, Object> req = parser.parseMap(pushEvent);
		ResponseEntity<?> result = null;
		var targetApp = new HashSet<String>();
		if (null != req.get(REF) && req.get(REF).equals(REFS_HEADS_MAIN)) {
			log.info("Processing github push event.");
			try {
				Map<String, Object> commitObj = parser.parseMap(objectMapper.writeValueAsString(req.get(HEAD_COMMIT)));
				String[] lines = commitObj.get(PR_MESSAGE).toString().split(NEXT_LINE_DELIMETER);
				Pattern jiraIdPattern = Pattern.compile(jiraIdPatternConfig, Pattern.CASE_INSENSITIVE);
				String prMessage = lines[lines.length - 1];
				Matcher jiraIdmatcher = jiraIdPattern.matcher(prMessage);

				if (prMessage.contains("#Regression")) {
					log.info("Regression will be executed ");
					triggerRegression(commitObj,objectMapper,targetApp);
				}else {
					var listForAddedFiles = convertToList(commitObj.get("added"), objectMapper);
					var listForDeletedFiles = convertToList(commitObj.get("removed"), objectMapper);
					var listForUpdatedFiles = convertToList(commitObj.get("modified"), objectMapper);
					List<String> combinedList = Stream.concat(Stream.concat(listForAddedFiles.stream(), listForDeletedFiles.stream()), listForUpdatedFiles.stream())
							.collect(Collectors.toList());
					List<String> testCaseIds = fetchTestCaseIdsfrmMLModel(combinedList);

          String testIds = String.join("%20or%20", testCaseIds );
          log.info("Excecuting Test cases: [{}]", testCaseIds);
					String command = getJenkinsBuildWithParamUrl("CompleteAutomation", testIds);
					if (isDeployDone()){
						triggerJenkinsJob(command);
					}

					return ResponseEntity.ok("Trigger Successful ");
				}

				if (jiraIdmatcher != null && jiraIdmatcher.find()) {
					var jiraId = jiraIdmatcher.group();
					log.info("Changes merged to main branch for Jira Id: {}", jiraId);
					if (isDeployDone() && (prMessage.contains("#QTest") || prMessage.contains("#Zephyr"))) {
						testCaseId = useQtest ? fetchQtestTestCases(parser, jiraId, objectMapper)
								: fetchZephyrTestCases(parser, jiraId, objectMapper);
						String testIds = String.join("%20or%20", testCaseId );
						log.info("Excecuting Test cases: [{}] for user story: {}", testIds, jiraId);
						String command = getJenkinsBuildWithParamUrl("CompleteAutomation", "%22"+testIds+"%22");

						triggerJenkinsJob(
								command);
					}

				} else {
					log.info(
							"No Jira Id found in the PR message. Can't process further. Please provide Jira-id in the PR message.");
					return ResponseEntity.unprocessableEntity().body(
							"No Jira Id found in the PR message. Can't process further. Please provide Jira-id in the PR message.");
				}
			} catch (Exception e) {
				log.error("Exception occured while processing request.", e);
				return ResponseEntity.status(result.getStatusCode()).build();
			}

		}
		return ResponseEntity.ok("Trigger Successful ");
	}

	private void triggerJenkinsJob(String command) {
		try {
			Process process = Runtime.getRuntime().exec(command);
			process.waitFor();
			if (process.exitValue() != 0) {
				log.info("Command: " + command);
				InputStream errorStream = process.getErrorStream();
				int c = 0;
				while ((c = errorStream.read()) != -1) {
					System.out.print((char)c);
				}
			}
			log.info("Complete Automation job triggered");
		} catch (IOException e) {
			log.error("IO error occured while executing the Automation job {}",e.getMessage());
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private List<String> fetchTestCaseIdsfrmMLModel(List<String> combinedList) {

		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		headers.setContentType(MediaType.APPLICATION_JSON);

		// Define the request payload
		String payload = "{ \"fileUpdates\": [ "+combinedList.stream()
				.map(item -> "\"" + item + "\"")
				.collect(Collectors.joining(", ")) + " ] }";

		System.out.println("payload:"+payload);

		// Create the request entity
		HttpEntity<String> requestEntity = new HttpEntity<>(payload, headers);

		// Make the POST request
		ResponseEntity<String> responseEntity = restTemplate.exchange(mlModelURL, HttpMethod.POST, requestEntity, String.class);

		// Get the response body
		String responseBody = responseEntity.getBody();

		List<String> testCaseIds = null;

		// Parse the response
		ObjectMapper objectMapper = new ObjectMapper();
		try {
			JsonNode rootNode = objectMapper.readTree(responseBody);

			JsonNode dataArray = rootNode.get("data");

			testCaseIds = new ArrayList<>();

			for (JsonNode dataNode : dataArray) {
				for (final JsonNode objNode : dataNode.get("featureID")) {
					testCaseIds.add("@"+objNode.asText());
				}
				int index = dataNode.get("index").asInt();
				String packageName = dataNode.get("packageName").asText();
				double score = dataNode.get("score").asDouble();

				System.out.println("Feature ID: " + testCaseIds);
				System.out.println("Index: " + index);
				System.out.println("Package Name: " + packageName);
				System.out.println("Score: " + score);
			}
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}

		return testCaseIds.stream().distinct().collect(Collectors.toList());
	}

	private void triggerRegression(Map<String, Object> commitObj,ObjectMapper objectMapper,Set<String> targetApp) {

		var listForAddedFiles = convertToList(commitObj.get("added"), objectMapper);
		var listForDeletedFiles = convertToList(commitObj.get("removed"), objectMapper);
		var listForUpdatedFiles = convertToList(commitObj.get("modified"), objectMapper);

		var moduleListForAdd = listForAddedFiles.stream()
				.map(val -> val.split("/")[val.split("/").length-3] )
				.collect(Collectors.toSet());

		var moduleListForDelete = listForDeletedFiles.stream()
				.map(val -> val.split("/")[val.split("/").length-3] )
				.collect(Collectors.toSet());

		var moduleListForUpdate = listForUpdatedFiles.stream()
				.map(val -> val.split("/")[val.split("/").length-3] )
				.collect(Collectors.toSet());

		targetApp.addAll(moduleListForAdd);
		targetApp.addAll(moduleListForDelete);
		targetApp.addAll(moduleListForUpdate);
		for(String module: targetApp) {
			String command = getJenkinsBuildUrl(module);
			try {
				Process process = Runtime.getRuntime().exec(command);
				log.info("Regression of automation job for module: {}", targetApp);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private List<String> convertToList(Object commitObj, ObjectMapper mapper) {
		var listForAddedFiles = mapper.convertValue(commitObj, List.class);
		List<String> tempList = new ArrayList<>();
		for (Object obj : listForAddedFiles) {
			if(!String.valueOf(obj).contains("/test/"))
				tempList.add(String.valueOf(obj));
		}
		return tempList;
	}

	private List<String> fetchZephyrTestCases(JsonParser parser, String jiraId, ObjectMapper objectMapper)
			throws JsonProcessingException {
		ResponseEntity<String> response;
		HttpHeaders header = new HttpHeaders();
		header.add("Authorization", zephyreKey);
		response = restTemplate.exchange(zephyreUrl, HttpMethod.GET, new HttpEntity<>(header), String.class,
				getZephyreParam(jiraId));
		List<Object> zephyrResponse = parser.parseList(response.getBody());
		var testcase = getTestCaseInfo(objectMapper, zephyrResponse);
		var testCaseId = testcase.stream().map(val -> parser.parseMap(val).get("key").toString())
				.map(str->"@"+str)
				.collect(Collectors.toList());
		log.info("testcase tags: {}", testCaseId);
		return testCaseId;
	}

	private List<String> fetchQtestTestCases(JsonParser parser, String jiraId, ObjectMapper objectMapper)
			throws JsonProcessingException {
		ResponseEntity<?> response;
		HttpHeaders header = new HttpHeaders();
		header.add("Authorization", qtestKey);
		response = restTemplate.exchange(qtestUrl, HttpMethod.GET, new HttpEntity<>(header), Object.class,
				getqtestParam(projectId));
		var qtestResponse = (List<Object>) (response.getBody());
		// var reqList = objectMapper.writeValueAsString(header)
		return getTestCaseInfo(objectMapper, qtestResponse, parser, jiraId);
		// return null;
	}

	private List<String> getTestCaseInfo(ObjectMapper objectMapper, List<Object> zephyrResponse)
			throws JsonProcessingException {
		var testcase = zephyrResponse.stream().map(val -> {
			String data = null;
			try {
				data = objectMapper.writeValueAsString(val);
			} catch (JsonProcessingException e) {
				e.printStackTrace();
			}
			return data;
		}).collect(Collectors.toList());
		return testcase;
	}

	private List<String> getTestCaseInfo(ObjectMapper objectMapper, List<Object> qTestResponse, JsonParser parser,
										 String issueId) throws JsonProcessingException {

		var userStories = qTestResponse.stream().map(val1 -> objectMapper.convertValue(val1, Map.class))
				.map(key -> key.get("requirements")).collect(Collectors.toList());

		List<QtestRequirement> reqList = new ArrayList<>();

		userStories.stream().forEach(us -> {
			var list = objectMapper.convertValue(us, new TypeReference<List<Object>>() {
			});
			list.stream().forEach(li -> {
				reqList.add(objectMapper.convertValue(li, QtestRequirement.class));
			});
		});

		var testCases = reqList.stream()
				.filter(li -> li.getName().contains(issueId) && Objects.nonNull(li.getLinkedTestcases()))
				.map(arg -> arg.getTestcases()).collect(Collectors.toList());
		return testCases.stream().filter(Objects::nonNull)
				.flatMap(list -> Arrays.asList(list.toString().split(", ")).stream()).collect(Collectors.toList());
	}

	private Map<String, String> getZephyreParam(String value) {
		Map<String, String> uriVariables = new HashMap<>();
		uriVariables.put("jiraId", value);
		return uriVariables;
	}

	private Map<String, String> getqtestParam(String value) {
		Map<String, String> uriVariables = new HashMap<>();
		uriVariables.put("projectId", value);
		return uriVariables;
	}

	private String getJenkinsBuildWithParamUrl(String jobName, String buildParams) {
		String jobUrl = "curl -X POST \"".concat(jenkinsUrl).concat("/").concat(jobName).concat("/")
				.concat("buildWithParameters?token=").concat(jobName).concat("&").concat("testsuite").concat("=")
				+(buildParams).concat("\"").concat(" ").concat("--user").concat(" ").concat(jenkinsUserName).concat(":")
				.concat(jenkinsuserAPIToken);
		log.info("job url {}", jobUrl);
		return jobUrl;

	}

	private String getJenkinsBuildUrl(String jobName) {
		String jobUrl ="curl -I ".concat(jenkinsUrl).concat("/").concat(jobName).concat("/").concat("build?token=").concat(jobName)
				.concat(" ").concat("--user").concat(" ").concat(jenkinsUserName).concat(":").concat(jenkinsuserPwd);
		log.info("job url {}", jobUrl);
		return jobUrl;
	}

	private boolean isDeployDone()  {
		int status=-1;
		do {
			URL url;
			try {
				Thread.sleep(30000);
				url = new URL(appHealthCheck);
				HttpURLConnection connection = (HttpURLConnection)url.openConnection();
				connection.connect();
				status = connection.getResponseCode();
				log.info("Code deployment status {}", status);
			} catch (MalformedURLException e) {
			} catch (IOException e) {
			} catch (InterruptedException e) {
			}finally {
				try {
					Thread.sleep(30000);
				} catch (InterruptedException e) {
				}
			}

		}while(status!=200);

		return status==200 ? true : false;
	}



}
