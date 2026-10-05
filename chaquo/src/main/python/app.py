import os
import requests
from importlib.machinery import SourceFileLoader
import json


def spider(cache, api):
    # 本地 py 源：直接按路径加载（导入本地配置时 ./py/x.py 会解析成绝对路径）
    local = local_path(api)
    if local:
        return load(local, api)
    name = os.path.basename(api)
    path = cache + '/' + name
    download(path, api)
    return load(path, api)


def local_path(api):
    # TVBox 配置里本地源有四种常见写法：file:///path、file://path、file:/path、/path。
    # 早期只认 file://，导致 file:/storage/... 落到 download()，把 api 字符串
    # 当成 Python 源码写盘 → "SyntaxError: invalid syntax (x.py, line 1)"。
    if api.startswith('file://'):
        p = api[len('file://'):]
    elif api.startswith('file:/'):
        p = api[len('file:'):]
    elif api.startswith('/'):
        p = api
    else:
        return None
    if p.startswith('localhost/'):
        p = p[len('localhost'):]
    if not p.startswith('/'):
        p = '/' + p
    return p if os.path.isfile(p) else None


def load(path, api):
    if not os.path.isfile(path):
        raise Exception('源文件不存在: ' + api)
    name = os.path.basename(path).split('.')[0]
    if not name:
        name = 'spider'
    return SourceFileLoader(name, path).load_module().Spider()


def download(path, api):
    if api.startswith('http'):
        writeFile(path, redirect(api).content)
    elif api.startswith('file:') or api.startswith('/') or api.startswith('.'):
        # 本地路径走到这里说明文件不存在：必须明确报错，绝不能把路径当初源码写盘。
        raise Exception('本地源文件不存在或无法读取: ' + api)
    else:
        writeFile(path, str.encode(api))


def writeFile(path, content):
    with open(path, 'wb') as f:
        f.write(content)


def redirect(url):
    rsp = requests.get(url, allow_redirects=False, verify=False)
    if 'Location' in rsp.headers:
        return redirect(rsp.headers['Location'])
    else:
        return rsp


def str2json(content):
    return json.loads(content)


def getDependence(ru):
    result = ru.getDependence()
    return result


def getName(ru):
    result = ru.getName()
    return result


def init(ru, extend):
    ru.init(extend)


def homeContent(ru, filter):
    result = ru.homeContent(filter)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def homeVideoContent(ru):
    result = ru.homeVideoContent()
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def categoryContent(ru, tid, pg, filter, extend):
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def detailContent(ru, array):
    result = ru.detailContent(str2json(array))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def searchContent(ru, key, quick, pg="1"):
    result = ru.searchContent(key, quick, pg)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def playerContent(ru, flag, id, vipFlags):
    result = ru.playerContent(flag, id, str2json(vipFlags))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def liveContent(ru, url):
    result = ru.liveContent(url)
    return result


def localProxy(ru, param):
    result = ru.localProxy(str2json(param))
    return result


def action(ru, action):
    result = ru.action(action)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def destroy(ru):
    ru.destroy()


def run():
    pass


if __name__ == '__main__':
    run()
