all: nodejs-components.png trireme-components.png

clean:
	rm -f nodejs-components.png trireme-components.png

nodejs-components.png: nodejs-components.dot
	tred nodejs-components.dot | unflatten -l 1 -f | dot -Tpng > nodejs-components.png

trireme-components.png: trireme-components.dot
	tred trireme-components.dot | unflatten -l 1 -f | dot -Tpng > trireme-components.png
	
