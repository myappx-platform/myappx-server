<html>
<head>
	<meta http-equiv="Content-Type" content="text/html; charset=utf-8">
	<link rel="stylesheet" href="/resources/templates/default/styles/template.css">
	<title>MyAppxWEB Error</title>
</head>
<body>
    <div class="world">
		<div class="parent">
		  <div class="inner">
		    <div class="tablecell">
		    <%
    			int status = response.getStatus();
			    if(status == 200)
			    {
			    	;
			    }else{
			    	out.println("<p class=\"statuscode\">Status Code</p>");
			    	out.println("<p class=\"status\">"+status+"</p>");
			    }
			%>
		    </div>
		  </div>
		</div>
	</div>
</body>
</html>