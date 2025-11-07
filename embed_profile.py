import base64
from pathlib import Path
from textwrap import wrap

icc_path = Path('CoatedFOGRA39.icc')
output_path = Path('coated_fogra39_profile.js')

data = base64.b64encode(icc_path.read_bytes()).decode('ascii')
chunks = wrap(data, 120)
with output_path.open('w', encoding='utf-8') as f:
    f.write('window.COATED_FOGRA39_BASE64 = "" +\n')
    for chunk in chunks:
        f.write(f'  "{chunk}" +\n')
    f.write('  "";\n')
