package com.orange;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.SingleSelectionModel;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class Micros {
	static final String POM_XML="pom.xml";

	static final String MASTER_BRANCH="master";
	static final String RELEASE_BRANCH="release";
	static final String DEVELOP_BRANCH="develop";

	static final String SONAR_KEY = "key";
	static final String SONAR_ID = "id";
	static final String SONAR_STATUS = "status";

	static HashMap<String, String> projects= new HashMap<>();

	static Set<String> excluded = new HashSet<>();

	static boolean debug=false;
	static boolean error=false;

	public static void main(String[] args) {

		for (int i=0;i<args.length;i++) {
			switch (args[i].toUpperCase()) {
			case "DEBUG":{ 
				debug=true; 
				break;
			}
			case "ERROR":{ 
				error=true; 
				break;
			}
			default: {
				System.out.println("argunmento invalido: "+args[i]);
				break;
			}
			}
		}

		String text="\nMICRO;ARTEFACTO;VERSION;RAMA;PARENT";

		excluded.add("XXXXXXXXX"); 	


		try {
			Map<String, String> projectsMap=getGitLab("projects?search=MIC", true);
//			Map<String, String> projectsMap=getGitLab("projects?search=FUN_MIC_CUSTOMERVIEW", true);
			String projectsStr=projectsMap.get("gitlab");
			while (projectsMap.get("next")!=null) {
				if (debug) System.out.println("next >> "+projectsMap.get("next"));
				projectsMap = getGitLab(projectsMap.get("next"), true);
				projectsStr+=projectsMap.get("gitlab");
				//arreglamos el JSON generado
				projectsStr=projectsStr.substring(0, projectsStr.indexOf("]["))+","+projectsStr.substring(projectsStr.indexOf("][")+2);
			}
			if (debug) System.out.println(projectsStr);

			//hay que completar el Json para que el parser lo entienda...
			JSONObject json = new JSONObject("{'projects':"+projectsStr+"}");
			JSONArray projectsArray = json.getJSONArray("projects");
			if (debug) System.out.println("#repos="+projectsArray.length());
			for (int i=0; i<projectsArray.length();i++) {
				JSONObject project= (JSONObject)projectsArray.get(i);
				String name=project.getString("name");
				if (!excluded.contains(name)) {
					if (debug) System.out.println(name);
					int id=project.getInt("id");
					projects.put(String.valueOf(id),name);
					text+=getMicros (name, id);	
					System.out.print("\r"+i+" ");
				} else {
					if (error) System.err.println("["+name+"] se excluye del analisis...");
				}
			}

		} catch (IOException e1) {
			e1.printStackTrace();
		}

		System.out.println(text);

	}


	static String getMicros (String project, int id) {
		String csv="\n";
		Map<String, String> m=parseMicro(project, id, POM_XML);
		csv+=project;
		csv+=";"+m.get("name");
		csv+=";"+m.get("version");
		csv+=";"+m.get("branch");
		csv+=";"+m.get("parent");
		return csv;
	}

	static Map<String, String>  parseMicro(String project, int id, String file) {

		String content="";
		HashMap<String, String> map= new HashMap<>();
		
		try {		
			try {
				//intentamos coger la version de produccion la primera
				content = getGitLabFile(id, file, MASTER_BRANCH, false);
				map.put("branch", MASTER_BRANCH);
			} catch (FileNotFoundException master) {
				if (debug) System.out.println(master.getMessage());
				try {
					//si no, cogemos la de UAT
					content = getGitLabFile(id, file, RELEASE_BRANCH, false);
					map.put("branch", RELEASE_BRANCH);
				} catch (FileNotFoundException release) {
					if (debug) System.out.println(release.getMessage());
					try {
						//por ultimo cogemos la de desarrollo
						content = getGitLabFile(id, file, DEVELOP_BRANCH, false);
						map.put("branch", DEVELOP_BRANCH);
					} catch (FileNotFoundException develop) {
						if (debug) System.out.println(develop.getMessage());
						map.put("name", file+" no encontrado");
						return map;
					}
				}
			}

			//System.out.println(project+">>" + content);
			
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();

			ByteArrayInputStream input = new ByteArrayInputStream(content.getBytes("UTF-8"));
			Document doc = builder.parse(input);

			Element root = doc.getDocumentElement();
			NodeList childs = root.getChildNodes();
			String name="sin nombre";
			String version="-1";
			String parent_version="-1";
			for (int i=0;i<childs.getLength();i++) {
				switch (childs.item(i).getNodeName()) {
				case "artifactId": {
					name=childs.item(i).getTextContent();
					break;
				}
				case "version":{
					version=childs.item(i).getTextContent();
					break;
				}
				case "parent":{
					parent_version=((Element)childs.item(i)).getElementsByTagName("version").item(0).getTextContent();
				}
				default: {
					
				}
					
				}
			}
			
			String vversion=formatVersion(version);
			String pversion=formatVersion(parent_version);
					
			map.put("name", name);
			map.put("version", vversion);
			map.put("parent", pversion);
			
		} catch (IOException eee) {
			System.err.println(eee.getMessage());
		} catch (ParserConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SAXException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return map;	

	}


	static String getGitLabFile (int id, String file, String branch, boolean v4) throws IOException {
		String urlFile=URLEncoder.encode(file, "UTF-8");

		urlFile=urlFile.replaceAll("\\.", "%2E").replaceAll("-", "%2D").replace("_","%5F");
		String url="";
		if (v4) {
			url = "https://torredecontrol.si.orange.es/gitlab/api/v4/projects/"+id+"/repository/files/"+urlFile+"/raw?ref="+branch;
		} else {//v3
			url = "https://torredecontrol.si.orange.es/gitlab/api/v3/projects/"+id+"/repository/files?file_path="+file+"&ref="+branch;			
		}
		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		// optional default is GET
		con.setRequestMethod("GET");

		//add request header
		con.setRequestProperty("PRIVATE-TOKEN", "nMW2c2Tjsxp5m-zqnZaG");

		int responseCode = con.getResponseCode();
		if (debug) System.out.println("\nSending 'GET' request to URL : " + url);
		if (responseCode != 200) {
			throw new FileNotFoundException("Archivo ["+file+"] no encontrado en la rama ["+branch+"] del proyecto ["+projects.get(String.valueOf(id))+"]: http ["+url+"] error code: "+responseCode);
		} else {
			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuffer response = new StringBuffer();

			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
			//print result
			if (debug) System.out.println(response.toString());
			if (v4) {
				return response.toString();
			} else {//v3
				try {
					JSONObject json = new JSONObject(response.toString());
					String content=json.getString("content");
					byte bytes[]=content.getBytes();
					byte[] valueDecoded = Base64.getDecoder().decode(bytes);
					if (debug) System.out.println("Decoded value is " + new String(valueDecoded));	
					return new String(valueDecoded);
				} catch (JSONException je) {
					if (error) System.err.println(je.getMessage());
					return ""; 
				}
			}
		}
	}

	static Map<String,String> getGitLab (String uri, boolean v4) throws IOException {
		String url="";
		String gitlab="";
		String next="";
		Map <String, String> answer=new HashMap<>();
		if (v4) {
			url = "https://torredecontrol.si.orange.es/gitlab/api/v4/"+uri;
		} else {//v3
			url = "https://torredecontrol.si.orange.es/gitlab/api/v3/"+uri;			
		}
		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		// optional default is GET
		con.setRequestMethod("GET");

		//add request header
		con.setRequestProperty("PRIVATE-TOKEN", "nMW2c2Tjsxp5m-zqnZaG");

		int responseCode = con.getResponseCode();
		if (responseCode != 200) {
			throw new FileNotFoundException("Recurso ["+uri+"] no encontrado: http ["+url+"] error code: "+responseCode);
		} else {
			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuffer response = new StringBuffer();

			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
			gitlab+=response.toString();
			//print gitlab
			if (debug) System.out.println("JSON => "+gitlab);
			answer.put("gitlab", gitlab);

			//miramos si está paginado y hay más paginas
			Map<String, List<String>> headers = con.getHeaderFields();
			if (debug) System.out.println("\nSending 'GET' request to URL : " + url + "headers: "+headers);
			List<String> links = headers.get("Link");
			if (debug) System.out.println(links);
			String[] nexts=links.get(0).split(",|;");
			for (int i=0;i<nexts.length;i++) {
				if (nexts[i].indexOf("next")>0) {
					next=nexts[i-1].substring(1, nexts[i-1].length()-1);		
					next= next.substring(next.indexOf("projects?"));
					if (debug) System.out.println("Next="+next);
					answer.put("next", next);
					break;
				} else {
					if (debug) System.out.println("No hay Next");
				}
			}
			if (debug) System.out.println("Headers => "+answer.toString());
			return answer;

		}
	}
	static String formatVersion (String version) {
		if (version=="") {
			return "-1";
		}
		version= version.replace("^", "");
		String ver[] = version.split("\\.|\\-|_");
		//System.out.println(Arrays.asList(ver));
		int v=0;
		for (int i=0;i<ver.length;i++) {
			try {
				v*=100;
				v+=Integer.parseInt(ver[i]);
			} catch (NumberFormatException e) {
				break;
			}
		}
		
		return String.valueOf(v);

	}
	
}
