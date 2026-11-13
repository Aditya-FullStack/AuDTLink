.PHONY: test demo clean

test:
	python3 tools/tests/test_airlink.py
	python3 tools/tests/test_kotlin_port.py

demo:
	python3 tools/airlink_cli.py doctor
	python3 tools/airlink_cli.py selftest

clean:
	rm -rf tools/**/__pycache__ tools/__pycache__ build
