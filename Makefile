.PHONY: clean env/bin/activate stats

include pb2.mk

export PYTHONPATH=$PYTHONPATH:./

PWD=`pwd`
ENV = env
PIP = $(PWD)/env/bin/pip
PYTHON = . env/bin/activate; cd python; python
JENKINS_NOSE_ARGS = --with-xunit
DISTRIBUTE = sdist bdist_wheel

all: env pb2_compile

test: env pb2_compile
	env/bin/nosetests $(NOSE_ARGS) python/tests/

test-jenkins:
	env/bin/nosetests python/tests/ $(JENKINS_NOSE_ARGS)

clean:
	rm -rf build/
	rm -rf dist/
	find protobuf_rpc/ -type f -name "*.pyc" -exec rm {} \;

package: all
	$(PYTHON) setup.py $(DISTRIBUTE)

release: env
	$(PYTHON) setup.py register -r livefyre
	$(PYTHON) setup.py $(DISTRIBUTE) upload -r livefyre

test-client: env
	$(PYTHON) example/search/search_client.py

test-server: env
	$(PYTHON) example/search/search_server.py

hammer: env
	$(PYTHON) tests/load_test/hammer.py

load-server-rpc: env
	$(PYTHON) tests/load_test/rpc.py

load-server-http: env
	$(PYTHON) tests/load_test/http.py

env: env/bin/activate
env/bin/activate: python/requirements.txt
	test -d env || virtualenv --no-site-packages env
	. env/bin/activate; pip install -r python/requirements.txt
	. env/bin/activate; pip install -e python/
	touch env/bin/activate


