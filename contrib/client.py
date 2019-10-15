import requests

class EclairClient():
    def __init__(self,host, port, password, service_name=None, session=None):
        self._host = host
        self._port = port
        self._password = password
        self._session = session
        if session is None:
            self._session = requests.Session()
        self._service_name = service_name
        self._url = "http://%s:%s/%s" % (self._host, self._port, self._service_name)

    def __getattr__(self, name):
        if name.startswith('__') and name.endswith('__'):
            # Python internal stuff
            raise AttributeError
        if self._service_name is not None:
            name = "%s.%s" % (self._service_name, name)
        return EclairClient(self._host, self._port, self._password, name, self._session )

    def __call__(self, *args, **kwargs):
        return self._session.post(self._url, data=kwargs,auth=('', self._password)).json()
