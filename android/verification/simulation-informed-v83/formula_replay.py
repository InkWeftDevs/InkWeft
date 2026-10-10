# Own verification program. Inputs are captured data, never executed drivers.
import argparse,hashlib,io,json,pathlib,zipfile,time
import numpy as np
import onnxruntime as ort
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--capture',required=True);p.add_argument('--assets',required=True);p.add_argument('--output',required=True);a=p.parse_args()
r=pathlib.Path(a.capture);assets=pathlib.Path(a.assets);opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
encoder=ort.InferenceSession(str(assets/'encoder_model.onnx'),opts,providers=['CPUExecutionProvider']);decoder=ort.InferenceSession(str(assets/'decoder_model.onnx'),opts,providers=['CPUExecutionProvider'])
report=json.loads((r/'reports/REC-03-model-analysis.json').read_text());results=[]
for case in report['cases']:
 rel=case['sourceResult']['path'].replace('\\','/').split('/Simulation-V80/',1)[1];zpath=r/rel;start=time.monotonic()
 with zipfile.ZipFile(zpath.with_name('model-trace.zip')) as z:
  trace=json.loads(z.read('attempt-1/trace.json'));png=z.read('attempt-1/formula-input-0.png');arr=np.asarray(Image.open(io.BytesIO(png)).convert('RGB'),dtype=np.uint8)
 # Match Kotlin Float operations (including intermediate rounding); grayscale blue channel copied to RGB.
 gray=arr[:,:,2].astype(np.float32)/np.float32(255);normalized=(gray-np.float32(.7931))/np.float32(.1738);data=np.repeat(normalized[None,None,:,:],3,axis=1)
 hidden=encoder.run(None,{'pixel_values':data})[0];ids=[0];decoded=[];stopped=False
 for step in range(384):
  scores=decoder.run(None,{'input_ids':np.array([ids],dtype=np.int64),'encoder_hidden_states':hidden})[0][0,-1,:];token=int(scores.argmax());decoded.append(token)
  if token==2:stopped=True;break
  if token<4:break
  ids.append(token)
 expected=[s['token_id'] for s in trace['formula-output-0']['formula_decoder_steps']]
 results.append({'case':case['id'],'inputPngSha256':hashlib.sha256(png).hexdigest(),'normalizedTensorSha256':hashlib.sha256(data.astype('<f4').tobytes()).hexdigest(),'capturedTokens':len(expected),'replayTokens':len(decoded),'equalCapturedTokenIds':expected==decoded,'eos':stopped,'hostElapsedMs':round((time.monotonic()-start)*1000)})
 print(case['id'], 'MATCH' if expected==decoded else 'DIFFERENT',flush=True)
 pathlib.Path(a.output).write_text(json.dumps({'scope':'HOST_CPU_REPLAY_OF_CAPTURED_PREPROCESSED_PNG_NOT_ANDROID_RASTER_OR_NEW_ACCURACY','onnxruntime':ort.__version__,'cases':results},indent=2)+'\n')
