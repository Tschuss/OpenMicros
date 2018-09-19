package com.orange;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

public class Micros {
	static final String APP_COMPONENTS_JSON="appComponents.json";
	static final String APP_COMPONENTS_TS="src/app/components/app-components.ts";
	static final String PACKAGE_CONFIG_JSON="package.config.json"; //PARA ANGULAR5

	static final String MASTER_BRANCH="master";
	static final String DEVELOP_BRANCH="develop";

	static final String SONAR_KEY = "key";
	static final String SONAR_ID = "id";
	static final String SONAR_STATUS = "status";
		
	static HashMap<String, String> projects= new HashMap<>();
	
	static Set<String> excluded = new HashSet<>();

	public static void main(String[] args) {
		
		String text="SPA;COMPONENTE;VERSION;RAMA;REPO";
	
		excluded.add("FRONTALUNIFICADO_ANGJS_SPABASE"); //POC de arquitectura
		excluded.add("ALTAMIRAOSP_APPMOV_SPA"); //Repositorio vacio
		excluded.add("PDV_ANG_SPA_PARRAS"); //algo del Parras :-(
		excluded.add("WEBDOCUMENTACION_ANG_SPACKYCO"); //Estas 3 de KYC no son buenas
		excluded.add("WEBDOCUMENTACION_ANG_SPACKYCA"); //  las correctas tienen a marca con todas las letras
		excluded.add("WEBDOCUMENTACION_ANG_SPACKYCJ"); 	
		
		try {
			Map<String, String> projectsMap=getGitLab("projects?search=_SPA", true);
			String projectsStr=(String)projectsMap.get("gitlab");
			while (projectsMap.get("next")!=null) {
				projectsMap = getGitLab((String)projectsMap.get("next"), true);
				projectsStr+=(String)projectsMap.get("gitlab");
				//arreglamos el JSON generado
				//System.out.println(projectsStr.indexOf("]["));
				projectsStr=projectsStr.substring(0, projectsStr.indexOf("]["))+","+projectsStr.substring(projectsStr.indexOf("][")+2);
			}
			//System.out.println(projectsStr);
			//hay que completar el Json para que el parser lo entienda...
			JSONObject json = new JSONObject("{'projects':"+projectsStr+"}");
			JSONArray projectsArray = json.getJSONArray("projects");
			for (int i=0; i<projectsArray.length();i++) {
				JSONObject project= (JSONObject)projectsArray.get(i);
				String name=project.getString("name");
				if (!excluded.contains(name)) {
					//System.out.println(name);
					int id=project.getInt("id");
					projects.put(String.valueOf(id),name);
					text+=getComponents (name, id);		
				} else {
					System.err.println("["+name+"] se excluye del analisis...");
				}
			}

		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		System.out.println(text);
		
	}


	static String getComponents (String project, int id) {
		String csv="";
		csv+=parseCommonComponents(project, id, APP_COMPONENTS_JSON);
		csv+=parseInnerComponents(project, id, APP_COMPONENTS_TS);
		csv+=parseCommonComponents(project, id, PACKAGE_CONFIG_JSON);
		return csv;
	}

	static String parseCommonComponents(String project, int id, String file) {
		
		String csv="";
		String content="";
		String branch=MASTER_BRANCH;
		
		try {		
			try {

				content = getGitLabFile(id, file, branch,false);
			} catch (FileNotFoundException e) {
				System.err.println(e.getMessage());
				try {
					branch=DEVELOP_BRANCH;
					content = getGitLabFile(id, file, branch,false);
				} catch (FileNotFoundException ee) {
					System.err.println(ee.getMessage());
					return "";
				}
			}
			
			JSONObject json = new JSONObject(content);
			JSONObject components;
			if (file==APP_COMPONENTS_JSON) {
				components= json.getJSONObject("appComponents");
			} else {
				components= json.getJSONObject("dependencies");
			}
			Iterator<String> it= components.keys();
			while (it.hasNext()) {
				String name = it.next();
				//System.out.println(name);
				String version = components.getString(name);
				version= version.replace("^", "");
				String ver[] = version.split("\\.|\\-|_");
				int vversion=Integer.parseInt(ver[0])*10000+Integer.parseInt(ver[1])*100+Integer.parseInt(ver[2]);
				csv+="\n"+project+";"+name+";"+vversion+";"+branch+";SI";
			}

			//System.out.println(csv);
		} catch (IOException eee) {
			System.err.println(eee.getMessage());
		}
		return csv;	

	}
	
	static String parseInnerComponents(String project, int id, String file) {
		
		String csv="";
		String content="";
		String branch=MASTER_BRANCH;
		
		try {		
			try {

				content = getGitLabFile(id, file, branch,false);
			} catch (FileNotFoundException e) {
				System.err.println(e.getMessage());
				try {
					branch=DEVELOP_BRANCH;
					content = getGitLabFile(id, file, branch, false);
				} catch (FileNotFoundException ee) {
					System.err.println(ee.getMessage());
					content="";
				}
			}

			//parserar el typescript
			int a=content.indexOf("app-components");
			if (a>0) {
				int b = content.indexOf("'", a+15); //comilla antes del componente
				while (b>0) {
					int c = content.indexOf("'", b+1); //comilla despues del componente
					String name = content.substring(b+1, c);
					//System.out.println(name);
					csv+="\n"+project+";"+name+";-1;"+branch+";NO";
					b=content.indexOf("'", c+1);
				} 
			}

		} catch (IOException eee) {
			System.err.println(eee.getMessage());
		}
		return csv;	

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
		//System.out.println("\nSending 'GET' request to URL : " + url);
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
			//System.out.println(response.toString());
			if (v4) {
				return response.toString();
			} else {//v3
				JSONObject json = new JSONObject(response.toString());
				String content=json.getString("content");
				byte bytes[]=content.getBytes();
				byte[] valueDecoded = Base64.getDecoder().decode(bytes);
				//System.out.println("Decoded value is " + new String(valueDecoded));	
				return new String(valueDecoded);
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
			//System.out.println(gitlab);
			answer.put("gitlab", gitlab);
			
			//miramos si está paginado y hay más paginas
			Map<String, List<String>> headers = con.getHeaderFields();
			//System.out.println("\nSending 'GET' request to URL : " + url + "headers: "+headers);
			List<String> links = headers.get("Link");
			String[] nexts=links.get(0).split(",|;");
			if (nexts[1].indexOf("next")>0) {
				next=nexts[0].substring(1, nexts[0].length()-1);		
				next= next.substring(next.indexOf("projects?"));
				//System.err.println("Next="+next);
				answer.put("next", next);
			} else {
				//System.err.println("No hay Next");
			}
			//System.out.println(answer.toString();
			return answer;
			
		}
	}


}
