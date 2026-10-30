"""Run at most two independent whole-origin histories, without retries."""
from concurrent.futures import ThreadPoolExecutor


def run_origins(origins, execute, *, workers=1):
    origins=list(origins)
    if not origins or type(workers) is not int or workers not in (1,2) or len(origins)!=len(set(origins)):
        raise ValueError('Historical origins must be unique and use one or two isolated workers')
    if workers==1:
        for origin in origins:execute(origin)
        return
    with ThreadPoolExecutor(max_workers=workers) as pool:
        # Finish the current bounded batch, retaining both actual outcomes.
        # A failure prevents any later batch from starting; nothing is retried.
        for offset in range(0,len(origins),workers):
            pending=[(origin,pool.submit(execute,origin)) for origin in origins[offset:offset+workers]]
            failed=[]
            for origin,future in pending:
                try:future.result()
                except Exception as error:failed.append((origin,error))
            if failed:
                raise RuntimeError('Historical origin workers failed: '+ '; '.join(name+': '+repr(error) for name,error in failed)) from failed[0][1]
