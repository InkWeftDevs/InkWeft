// Parse data only. No require/eval/import of any APK JavaScript.
const fs=require('fs'),crypto=require('crypto');
const [root,out,dependencies]=process.argv.slice(2);if(!root||!out||!dependencies)throw Error('Usage: node inspect-modules.cjs WEB_JS_DIRECTORY PRIVATE_OUTPUT NPM_PREFIX');fs.mkdirSync(out,{recursive:true});
const parser=require(require('path').resolve(dependencies,'node_modules/@babel/parser'));
const generator=require(require('path').resolve(dependencies,'node_modules/@babel/generator')).default;
const sources=[['editor-a8aa935f.js',[9420]],['vendors-e6885a98.js',[77425,38251]]];const rows=[];
for(const [name,ids] of sources){
 const source=fs.readFileSync(root+'/'+name,'utf8');const ast=parser.parse(source,{sourceType:'unambiguous'});const stack=[ast];const found=[];
 while(stack.length){
  const n=stack.pop();if(!n||typeof n!=='object')continue;
  if(n.type==='ObjectProperty'&&n.key?.type==='NumericLiteral'&&ids.includes(n.key.value)&&n.value?.type==='FunctionExpression')found.push(n);
  for(const [key,value] of Object.entries(n)){if(['loc','extra','comments','tokens'].includes(key))continue;if(Array.isArray(value))stack.push(...value);else if(value&&typeof value==='object')stack.push(value);}
 }
 for(const id of ids){const matches=found.filter(n=>n.key.value===id);if(matches.length!==1)throw Error('Ambiguous module '+id);const n=matches[0];const text=generator(n.value,{comments:false,compact:false}).code;fs.writeFileSync(out+'/module-'+id+'.js',text);const start=Buffer.byteLength(source.slice(0,n.value.start)),end=Buffer.byteLength(source.slice(0,n.value.end));const raw=Buffer.from(source).subarray(start,end);rows.push({asset:name,module:id,rawStartByte:start,rawEndByteExclusive:end,rawSha256:crypto.createHash('sha256').update(raw).digest('hex'),formattedLines:text.split('\n').length});}
}
fs.writeFileSync(out+'/module-identity.json',JSON.stringify(rows,null,2)+'\n');console.log(JSON.stringify(rows));
