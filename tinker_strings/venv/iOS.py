#
# Created by Raimundas Sakalauskas on 2019-08-29.
# Copyright (c) 2019 Particle. All rights reserved.
#

import json
from datetime import date

def outputStrings(json, key, output):
    for n in json:
        if isinstance(json[n], dict):
            key.append(n)
            outputStrings(json[n], key, output)
            key.pop()
        else:
            output.write('\"%s.%s\" = \"%s\";\n' % ('.'.join(key), n, json[n]))
    output.write('\n')



def outputSwift(json, key, output):
    for n in json:
        if isinstance(json[n], dict):
            output.write('%senum %s {\n' % ('\t' * len(key), n))
            key.append(n)
            outputSwift(json[n], key, output)
            key.pop()
            output.write('%s}\n' % ('\t' * len(key)))
        else:
            output.write('%sstatic let %s = "%s.%s".tinkerLocalized()\n' % ('\t' * len(key), n, '.'.join(key), n))

#open data as
data = json.load(open('TinkerStrings.json'))
key = []

#prepare for the output
output = open("TinkerStrings.strings", "w")
output.write('// Generated on %s by iOS.py\n\n' % date.today())
outputStrings(data, key, output)
output.close()

#prepare for the output
output = open("TinkerStrings.swift", "w")

output.write('// Generated on %s by iOS.py\n\n' % date.today())

with open("TinkerStringsHeader.swift") as f:
    lines = f.readlines()
    output.writelines(lines)


outputSwift(data, key, output)
output.close()


