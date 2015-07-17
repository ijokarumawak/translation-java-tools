package com.couchbase.translation.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;

import com.couchbase.client.java.CouchbaseCluster;
import com.couchbase.client.java.document.JsonDocument;
import com.couchbase.client.java.document.json.JsonArray;
import com.couchbase.client.java.document.json.JsonObject;

public class MergeTranslation {

	private static class Args {
		private String fileUriReplacement;
		private String fileUriRegex;
		private String file;
		private String doc;
	}
	
	private static class SentenceLocation {
		private int start;
		private int end;
		private String en;
		private String ja;
		@Override
		public String toString() {
			return super.toString() + ":" + start + "," + end + "::" + en;
		}
	}
	
	public static void main(String[] args) throws ParseException, IOException {

		Args options = parseArgs(args);
		
		if(options.doc == null || options.doc.isEmpty()){
			options.doc = readDocId();
		}
		
		JsonDocument doc = loadCouchbaseDocument(options.doc);
		
		if(options.file == null || options.file.isEmpty()){
			String uri = doc.content().getString("uri");
			if(options.fileUriRegex != null && !options.fileUriRegex.isEmpty()){
				uri = uri.replaceAll(options.fileUriRegex, options.fileUriReplacement);
			}
			options.file = uri;
			if(!confirmFileName(options.file)){
				System.out.println("Quit.");
				return;
			}
		}

		String content = FileUtils.readFileToString(new File(options.file), "UTF-8");

		List<SentenceLocation> sentenceLocations = findSentenceLocations(options, content, doc);
		
		int contentPos = 0;
		StringBuilder result = new StringBuilder();
		
		List<SentenceLocation> skippedSentences = new ArrayList<>();
		for(SentenceLocation loc : sentenceLocations){
			// Add content prior to this location.
			result.append(content.subSequence(contentPos, loc.start));
			
			String original = (String) content.subSequence(loc.start, loc.end);
			if(confirmMerge(original, loc.ja)){
				result.append(loc.ja);
			} else {
				result.append(original);
				System.out.println("Skipped a sentence.");
				skippedSentences.add(loc);
			}
			contentPos = loc.end;
		}
		// Add the last part.
		result.append(content.substring(contentPos));
		
		
		if(confirmOverwrite(options.file)){
			FileUtils.write(new File(options.file), result, "UTF-8");
			System.out.println("## Done.");
			
			if(!skippedSentences.isEmpty()){
				System.out.println("## These sentences were not merged. Please merge it manually:");
				for (SentenceLocation location : skippedSentences) {
					System.out.println(location.start + ": " + location.en);
				}
			}
		} else {
			System.out.println("## Just showing the result:");
			System.out.println(result);
		}
		
		
	}
	
	private static JsonDocument loadCouchbaseDocument(String docId){
		CouchbaseCluster cluster = CouchbaseCluster.create("vm.sherlock");
		
		JsonDocument doc = cluster.openBucket("translation").get(docId);
		
		cluster.disconnect();
		return doc;
	}

	private static List<SentenceLocation> findSentenceLocations(Args options, String content, JsonDocument doc) {
		JsonObject translations = doc.content();
		JsonArray sentences = translations.getArray("sentences");
		
		int contentPos = 0;
		List<SentenceLocation> sentenceLocations = new ArrayList<>();
		for(int i = 0; i < sentences.size(); i++){
			JsonObject sentence = sentences.getObject(i).getObject("txt");
			String en = sentence.getString("en");
			String ja = sentence.getString("ja");
			
			// If these are the same, there's no need to merge in the first place.
			if(en.equals(ja)){
				continue;
			}
			
			SentenceLocation location = new SentenceLocation();
			
			location.start = findStart(content, contentPos, en);
			if(location.start < 0) {
				throw new RuntimeException("Couldn't find a sentence in the file: " + en);
			}
			
			location.end = findEnd(content, location.start, en);
			if(location.end < 0) {
				throw new RuntimeException("Couldn't find the end index for a sentence in the file: " + en);
			}
			
			location.en = en;
			location.ja = ja;
			
			if(location.start > location.end){
				throw new RuntimeException("location.start > location.end, please check the content in the file: " + location);
			}
			
			System.out.println(location);
			sentenceLocations.add(location);
			
			contentPos = location.end;
			
		}
		return sentenceLocations;
	}
	
	private static boolean confirmFileName(String file) throws IOException {
		System.out.println("### Proceed with this file? (y/n) : y");
		System.out.println(file);
		
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		String input = br.readLine();
		if("y".equalsIgnoreCase(input) || input == null || input.isEmpty()){
			return true;
		}
		
		return false;

	}
	
	private static String readDocId() throws IOException {
		System.out.println("### Input docId in Couchbase Server :");
		
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		String input = br.readLine();
		return input;
	}

	private static boolean confirmOverwrite(String file) throws IOException {
		System.out.println("### Overwrite the file? (y/n) : y");
		System.out.println(file);
		
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		String input = br.readLine();
		if("y".equalsIgnoreCase(input) || input == null || input.isEmpty()){
			return true;
		}
		
		return false;

	}
	
	private static boolean confirmMerge(String original, String translation) throws IOException {
		
		System.out.println("### Merge this sentence? (y/n) : y");
		System.out.println(original);
		System.out.println(translation);
		
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		String input = br.readLine();
		if("y".equalsIgnoreCase(input) || input == null || input.isEmpty()){
			return true;
		}
		
		return false;

	}
	
	private static int findStart(String content, int contentPos, String target){
		int indexOf = content.indexOf(target, contentPos);
		if(indexOf > -1) return indexOf;
		// Find the longest match.
		target = target.substring(0, target.length() - 1);
		// Couldn't find any.
		if(target.length() == 0) return -1;
		return findStart(content, contentPos, target);
	}

	private static int findEnd(String content, int contentPos, String target){
		int indexOf = content.indexOf(target, contentPos);
		if(indexOf > -1) return indexOf + target.length();
		// Find the longest match.
		target = target.substring(1);
		// Couldn't find any.
		if(target.length() == 0) return -1;
		return findEnd(content, contentPos, target);
	}

	private static Args parseArgs(String[] args) throws ParseException {
		Options options = new Options();
		options.addOption(Option.builder("f").argName("file")
				.desc("A file which will be overwritten with the translation.")
				.hasArg().longOpt("file").build());
		options.addOption(Option.builder("d").argName("doc")
				.desc("A translation document ID. A doc ID in Couchbase Server.")
				.hasArg().longOpt("doc").build());
		options.addOption(Option.builder().argName("file uri regex")
				.desc("A regular expression to convert URI in the JSON document into file path.")
				.hasArg().longOpt("file-uri-regex").build());
		options.addOption(Option.builder().argName("file uri replacement")
				.desc("A replacement string to convert URI in the JSON document into file path.")
				.hasArg().longOpt("file-uri-replace").build());
		
		CommandLineParser parser = new DefaultParser();
		CommandLine commandLine = parser.parse(options, args);
		
		Args opt = new Args();
		opt.file = commandLine.getOptionValue('f');
		opt.doc = commandLine.getOptionValue('d');
		opt.fileUriRegex = commandLine.getOptionValue("file-uri-regex");
		opt.fileUriReplacement = commandLine.getOptionValue("file-uri-replace");
		return opt;
	}

}
