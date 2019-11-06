#!/usr/bin/env python3

import io
import json
import pathlib
import re
import sys

from datetime import datetime
from xml.sax import saxutils


STRING_RESOURCE_FILE_HEADER = """\
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated on {0} by write_android_strings.py -->
<resources>
""".format(datetime.now())
STRING_RESOURCE_FILE_FOOTER = '</resources>\n'
TEMPLATE_STRING_RESOURCE = '    <string name="{0}">{1}</string>\n'


def convert_json_files_to_xml(json_doc_paths, xmls_output_path):
    
    def camel_to_snake(filename):
        s1 = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', filename)
        return re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1).lower()    
    
    def convert(path):
        with open(path, 'r') as jsonfile:
            tinker_json_obj = json.load(jsonfile)
    
        xml_output_path = pathlib.Path(xmls_output_path, camel_to_snake(path.stem) + ".xml")
        
        with open(xml_output_path, 'w') as out:
            out.write(STRING_RESOURCE_FILE_HEADER)
            write_json_as_xml(tinker_json_obj, [], out)
            out.write(STRING_RESOURCE_FILE_FOOTER)
    
    for path in json_doc_paths:
        print(f"Converting {path.name} to xml...")
        convert(path)
    

def write_json_as_xml(json_obj, key_parts, out_stream):
    for key, val in json_obj.items():
        key_parts.append(key)
        
        if isinstance(val, str):
            escaped = saxutils.escape(val)
            brackets_replaced = escaped.replace("{{", "{").replace("}}", "}").replace("'", "\\'")
            output = TEMPLATE_STRING_RESOURCE.format('_'.join(key_parts), brackets_replaced)
            out_stream.write(output)
        else:
            write_json_as_xml(val, key_parts, out_stream)
        
        key_parts.pop()


def main():
    if len(sys.argv) < 3:
        sys.exit("Usage: write_android_strings.py jsons_path xmls_path")
    
    jsons_path = pathlib.Path(sys.argv[1])
    xmls_path = pathlib.Path(sys.argv[2])
    
    try:
        json_paths = [x for x in pathlib.Path(jsons_path).glob('*.json')]
    except:
        json_paths = []
    
    if not json_paths:
        sys.exit("No files ending in '.json' found in path '{0}'".format(jsons_path))
    
    convert_json_files_to_xml(json_paths, xmls_path)
    

if __name__ == "__main__":
    main()

