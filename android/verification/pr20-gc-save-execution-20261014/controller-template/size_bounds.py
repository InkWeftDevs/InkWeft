"""Conditional text capacity arithmetic; no native evidence."""
def wire(n):
 return sum(4*((min(32768,n-i)+2)//3)+1024 for i in range(0,n,32768))+8192
def calculate(contract):
 t=contract['transport'];m=t['methods'];fixed=sum(t['fixed_text_buckets_bytes'].values());assert fixed==3*1024*1024
 for b in m.values():assert b['failure_wire_max_bytes']==wire(b['failure_raw_max_bytes'])
 normal=sum(m[str(i)]['normal_raw_max_bytes']+m[str(i)]['normal_record_end_max_bytes'] for i in [6,7,8])+fixed
 first=m['6']['normal_raw_max_bytes']+m['6']['normal_record_end_max_bytes']
 cases={'all_methods_success':normal,'first_failure_in_guards':m['1']['failure_raw_max_bytes']+m['1']['failure_wire_max_bytes']+fixed,'first_failure_in_GC_worst_last_trial':first+m['7']['failure_raw_max_bytes']+m['7']['failure_wire_max_bytes']+fixed,'first_failure_in_save_worst_last_arm':sum(m[str(i)]['normal_raw_max_bytes']+m[str(i)]['normal_record_end_max_bytes'] for i in [6,7])+m['8']['failure_raw_max_bytes']+m['8']['failure_wire_max_bytes']+fixed}
 assert cases==t['sizing_cases_bytes'];maximum=max(cases.values());assert maximum<=t['total_text_limit_bytes']
 return {'kind':'CONDITIONAL_SYNTHETIC_CAPACITY_NOT_NATIVE','cases_bytes':cases,'maximum_case_bytes':maximum,'remaining_bytes':t['total_text_limit_bytes']-maximum,'fixed_buckets_bytes':t['fixed_text_buckets_bytes'],'limit_bytes':t['total_text_limit_bytes'],'no_derived_records_uploaded':True,'oversize_policy':'FAIL_CLOSED_NO_TRUNCATION'}
