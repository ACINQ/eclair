# Errors

> Example error response: 

```json
{
  "error": "Request is missing required form field 'description'"
}
```

The Eclair API responds in an uniform way to all errors, HTTP codes are mapped to the following meaning:

Error Code | Error Name | Description
---------- | -------- | ----------
400 | Bad Request | Your request contains malformed or missing parameters 
401 | Unauthorized | Wrong or no auth supplied
404 | Not Found | The specified method could not be found.
500 | Internal Server Error | We had a problem with our server. Try again later.

