"""Cloud Chromium checks for this isolated, synthetic-data visual prototype.

Run with an existing server: python verify.py http://127.0.0.1:4180
Requires the environment's Playwright and Chromium, not application dependencies.
"""
import hashlib
import json
import sys
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE = Path(__file__).resolve().parent
OUT = BASE / 'evidence'
OUT.mkdir(exist_ok=True)
URL = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:4180'
checks, errors, requests, geometry_evidence = [], [], [], []


def check(name, condition):
    checks.append({'check': name, 'passed': bool(condition)})
    if not condition:
        raise AssertionError(name)


def paper_geometry(page):
    return page.evaluate("""() => {
      const paper=document.getElementById('paper'), area=document.getElementById('paperArea'), ink=document.getElementById('ink');
      const rect=e=>{const r=e.getBoundingClientRect();return [r.x,r.y,r.width,r.height]};
      const style=getComputedStyle(paper), matrix=ink.getScreenCTM();
      return {paper:rect(paper), viewport:rect(area), transform:style.transform, zoom:style.zoom,
        scroll:[area.scrollLeft,area.scrollTop,scrollX,scrollY], inkMatrix:[matrix.a,matrix.b,matrix.c,matrix.d,matrix.e,matrix.f]};
    }""")


def verify_layer_interactions(page, check):
    for width, height in [(1440, 1000), (375, 800)]:
        page.set_viewport_size({'width': width, 'height': height})
        page.goto(URL, wait_until='networkidle')
        page.locator('#paperArea').evaluate('e=>e.scrollTop=120')
        before=paper_geometry(page)
        paper=page.locator('#paper').bounding_box()
        x=paper['x']+50
        y=page.locator('#paperArea').bounding_box()['y']+70
        page.mouse.move(x,y);page.mouse.down();page.mouse.move(x+70,y+8,steps=8);page.mouse.up()
        check(f'{width}: paper can receive trial ink before opening layers', page.locator('#ink path').count()==1)
        ink_before=page.locator('#ink').inner_html()
        page.locator('#layerToggle').click()
        check(f'{width}: scrolled paper and ink transform remain fixed under popup',paper_geometry(page)==before)
        box=page.locator('#layerPanel').bounding_box()
        check(f'{width}: layer popup overlaps the retained paper',
              box['x']<paper['x']+paper['width'] and box['x']+box['width']>paper['x'] and box['y']<paper['y']+paper['height'])
        # Dragging the opacity control must not start or erase a stroke behind it.
        slider=page.locator('#opacity').bounding_box()
        page.mouse.move(slider['x']+slider['width']*.8,slider['y']+slider['height']/2)
        page.mouse.down();page.mouse.move(slider['x']+slider['width']*.4,slider['y']+slider['height']/2,steps=8);page.mouse.up()
        page.locator('#opacity').fill('100')
        check(f'{width}: popup gestures do not leak through into ink',page.locator('#ink').inner_html()==ink_before)
        # The dismissal gesture is consumed rather than becoming a click-sized stroke.
        page.mouse.click(x,y)
        page.wait_for_function("!document.getElementById('layerPanel').open")
        check(f'{width}: outside click closes without writing and returns focus',
              page.locator('#ink').inner_html()==ink_before and paper_geometry(page)==before and page.locator('#layerToggle').evaluate('e=>e===document.activeElement'))
        page.mouse.move(x,y+25);page.mouse.down();page.mouse.move(x+70,y+30,steps=8);page.mouse.up()
        check(f'{width}: writing resumes after popup dismissal',page.locator('#ink path').count()==2)
        page.locator('#layerToggle').click();page.keyboard.press('Escape')
        page.wait_for_function("!document.getElementById('layerPanel').open")
        check(f'{width}: Escape closes popup without moving paper',paper_geometry(page)==before and page.locator('#layerToggle').evaluate('e=>e===document.activeElement'))
        page.locator('#layerToggle').click()
        page.go_back()
        page.wait_for_function("!document.getElementById('layerPanel').open")
        check(f'{width}: browser Back dismisses popup instead of leaving the demo',page.url.rstrip('/')==URL.rstrip('/') and paper_geometry(page)==before and page.locator('#layerToggle').evaluate('e=>e===document.activeElement'))
        page.locator('#mapToggle').click()
        page.locator('[data-section=instant]').click();page.locator('#sourceJump').click()
        with_map=paper_geometry(page);mapbox=page.locator('#mapPanel').bounding_box()
        page.locator('#layerToggle').click()
        page.go_back()
        page.wait_for_function("!document.getElementById('layerPanel').open")
        check(f'{width}: Back closes layers while keeping map selection, bounds and source context',
              page.locator('#mapPanel').is_visible() and page.locator('#mapPanel').bounding_box()==mapbox and paper_geometry(page)==with_map and page.locator('[data-section=instant]').get_attribute('aria-pressed')=='true')


with sync_playwright() as p:
    browser = p.chromium.launch(executable_path='/usr/bin/chromium', headless=True,
                                args=['--no-sandbox'])
    page = browser.new_page(viewport={'width': 1440, 'height': 1000}, device_scale_factor=1)
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.on('request', lambda request: requests.append(request.url))
    response = page.goto(URL, wait_until='networkidle')
    check('HTTP document renders successfully', response.status == 200)
    check('Default layout is writing with an unobscured paper',
          page.locator('#side').evaluate("e=>getComputedStyle(e).display==='none'"))
    text = page.locator('#paperContent').inner_text()
    check('Same substantive calculus note, definitions and example are present',
          len(text) > 500 and 'y = 2x − 1' in text and '左右极限不同' in text)

    def shot(name):
        page.wait_for_timeout(250)  # Finish the reference-inspired 200ms material transition.
        page.screenshot(path=str(OUT / name))

    shot('blend-writing.png')
    paper_pixels = []
    for style in ['blend', 'card', 'liquid']:
        page.locator(f'.styles [data-style={style}]').click()
        page.wait_for_timeout(250)
        check(style + ': selected style is truthful',
              page.locator(f'.styles [data-style={style}]').get_attribute('aria-pressed') == 'true'
              and page.locator('html').get_attribute('data-style') == style)
        check(style + ': paper is opaque and unfiltered', page.locator('#paper').evaluate(
            "e=>{const s=getComputedStyle(e);return s.backgroundColor==='rgb(255, 254, 251)' && s.filter==='none' && s.backdropFilter==='none'}"))
        check(style + ': note text is unchanged', page.locator('#paperContent').inner_text() == text)
        box = page.locator('#paper').bounding_box()
        paper_pixels.append(page.screenshot(clip={'x': box['x'] + 4, 'y': box['y'] + 4,
                                                'width': box['width'] - 8, 'height': 600}))
        before = paper_geometry(page)
        page.locator('#layerToggle').click()
        opened=paper_geometry(page)
        check(style + ': layer popup leaves paper geometry and view transform unchanged', opened == before)
        geometry_evidence.append({'style':style,'viewport':[1440,1000],'map_open':False,'before':before,'open':opened})
        shot(style + '-layers.png')
        page.locator('#closeLayers').click()
        check(style + ': closing layers preserves paper and restores trigger focus', paper_geometry(page) == before and page.locator('#layerToggle').evaluate('e=>e===document.activeElement'))
        page.locator('#mapToggle').click()
        shot(style + '-map.png')
        check(style + ': mind map keeps the paper visible',
              page.locator('#paperArea').bounding_box()['width'] > 650
              and page.locator('#mapPanel').is_visible())
        page.locator('#mapToggle').click()
    check('Visible paper pixels are identical across all three material styles',
          len({hashlib.sha256(data).hexdigest() for data in paper_pixels}) == 1)

    page.locator('.styles [data-style=blend]').click()
    for theme in ['sky', 'warm', 'mist']:
        page.locator(f'[data-theme={theme}].theme-button').click()
        check(theme + ': environment theme switches',
              page.locator('html').get_attribute('data-theme') == theme)

    page.get_by_role('button', name='朱砂', exact=True).click()
    page.get_by_role('button', name='粗笔 5', exact=True).click()
    paper = page.locator('#paper').bounding_box()
    x, y = paper['x'] + 100, paper['y'] + 120
    page.mouse.move(x, y)
    page.mouse.down()
    page.mouse.move(x + 150, y + 15, steps=18)
    page.mouse.up()
    path = page.locator('#ink path')
    check('Selected pen color and width affect a real trial stroke',
          path.count() == 1 and path.get_attribute('stroke') == '#a35550'
          and path.get_attribute('stroke-width') == '5')
    page.get_by_role('button', name='撤销试写', exact=True).click()
    check('Undo removes the trial stroke', page.locator('#ink path').count() == 0)
    page.get_by_role('button', name='重做试写', exact=True).click()
    check('Redo restores the trial stroke', page.locator('#ink path').count() == 1)

    page.locator('#layerToggle').click()
    page.get_by_role('button', name='隐藏学习批注', exact=True).click()
    check('Last writable layer is not hidden', page.get_by_role('button', name='隐藏学习批注', exact=True).count() == 1)
    page.locator('[data-layer-select=draft]').click()
    check('Inspecting a hidden layer does not change the writing target', '学习批注' in page.locator('#layerStatus').inner_text())
    page.get_by_role('button', name='显示推导草稿', exact=True).click()
    check('Making a hidden layer visible does not silently select it', '学习批注' in page.locator('#layerStatus').inner_text())
    page.locator('[data-layer-select=notes]').click()
    page.get_by_role('button', name='隐藏学习批注', exact=True).click()
    page.wait_for_function("getComputedStyle(document.querySelector('.annotation-text')).opacity==='0'")
    check('Hiding the active layer hides its ink and original annotations',
          page.locator('#ink path').count() == 0 and
          page.locator('.annotation-text').first.evaluate("e=>getComputedStyle(e).opacity==='0'"))
    page.locator('#closeLayers').click()
    page.mouse.move(x, y + 50)
    page.mouse.down()
    page.mouse.move(x + 70, y + 50)
    page.mouse.up()
    page.locator('#layerToggle').click()
    page.get_by_role('button', name='显示学习批注', exact=True).click()
    check('Re-showing a hidden current layer requires explicit writing selection', '暂停书写' in page.locator('#layerStatus').inner_text())
    page.locator('[data-layer-select=notes]').click()
    check('Hidden layers reject trial ink without silently switching layers',
          page.locator('#ink path').count() == 1)
    page.locator('#opacity').fill('40')
    check('Layer opacity changes the rendered ink',
          page.locator('#ink path').get_attribute('opacity') == '0.4')
    page.locator('#opacity').fill('100')
    page.locator('[data-layer-select=draft]').click()
    check('Draft selection and visibility both work',
          page.locator('#draftNote').is_visible() and '推导草稿' in page.locator('#layerStatus').inner_text())
    page.locator('#closeLayers').click()
    page.get_by_role('button', name='荧光笔', exact=True).click()
    paper = page.locator('#paper').bounding_box()
    x, y = paper['x'] + 110, paper['y'] + 200
    page.mouse.move(x, y)
    page.mouse.down()
    page.mouse.move(x + 140, y + 2, steps=15)
    page.mouse.up()
    check('Highlighter creates translucent ink on selected layer',
          page.locator('#ink path').count() == 2 and
          page.locator('#ink path').last.get_attribute('opacity') == '0.3')
    page.get_by_role('button', name='擦除试写笔迹', exact=True).click()
    page.mouse.click(x + 50, y + 1)
    check('Eraser removes hit trial stroke only', page.locator('#ink path').count() == 1)
    page.locator('#undo').click()
    check('Erasing a stroke can itself be undone', page.locator('#ink path').count() == 2)
    page.locator('#redo').click()
    check('Erasing can be redone', page.locator('#ink path').count() == 1)
    page.locator('#readToggle').click()
    check('Reading mode allows underlying document interaction',
          page.locator('#ink').evaluate("e=>getComputedStyle(e).pointerEvents==='none'"))
    page.mouse.move(x, y)
    page.mouse.down()
    page.mouse.move(x + 50, y + 2)
    page.mouse.up()
    check('Reading mode does not add ink', page.locator('#ink path').count() == 1)

    page.locator('#layerToggle').click()
    page.get_by_role('button', name='隐藏推导草稿', exact=True).click()
    page.locator('[data-layer-select=notes]').click()
    page.locator('#closeLayers').click()
    page.locator('[data-tool=pen]').click()
    page.locator('#readToggle').click()
    page.get_by_role('button', name='中笔 3', exact=True).click()
    page.get_by_role('button', name='靛蓝', exact=True).click()
    page.wait_for_selector('#toast', state='hidden')
    page.locator('#mapToggle').click()
    page.locator('[data-section=example]').click()
    page.locator('#sourceJump').click()
    area, target = page.locator('#paperArea').bounding_box(), page.locator('#example').bounding_box()
    check('Map source action scrolls actual corresponding section into view',
          area['y'] <= target['y'] < area['y'] + area['height'] - 100)
    shot('blend-source-jump.png')
    page.locator('#mapToggle').click()
    check('Closing map retains actual source scroll context', page.locator('#paperArea').evaluate('e=>e.scrollTop') > 100)
    page.locator('#info').click()
    check('Prototype limitations are visible in the dialog', page.locator('#about').is_visible()
          and '不保存' in page.locator('#about').inner_text())
    page.keyboard.press('Escape')
    check('Dialog supports Escape and returns focus', not page.locator('#about').is_visible()
          and page.locator('#info').evaluate('e=>e===document.activeElement'))
    page.reload(wait_until='networkidle')
    check('Reload resets trial ink and does not pretend to save', page.locator('#ink path').count() == 0)
    check('No persistent storage is written', page.evaluate('localStorage.length===0 && sessionStorage.length===0'))

    for width, height in [(1200, 800), (800, 1100), (390, 844), (375, 800)]:
        page.set_viewport_size({'width': width, 'height': height})
        page.goto(URL + '?panel=map', wait_until='networkidle')
        page.wait_for_timeout(200)
        check(f'{width}: opening map does not automatically navigate the note', page.locator('#paperArea').evaluate('e=>e.scrollTop') == 0)
        check(f'{width}: no page-level horizontal overflow', page.evaluate('document.documentElement.scrollWidth===innerWidth'))
        area = page.locator('#paperArea').bounding_box()
        panel = page.locator('#mapPanel').bounding_box()
        check(f'{width}: map is not fullscreen and source stays readable',
              area['height'] >= 180 and area['width'] >= 280 and panel['height'] >= 220)
        if width < 650:
            page.locator('#sourceJump').click()
            instant = page.locator('#instant').bounding_box()
            check(f'{width}: selected definition is actually visible with map',
                  area['y'] <= instant['y'] < area['y'] + area['height'] - 100)
            last = page.locator('[data-section=example]').bounding_box()
            tree = page.locator('.map-tree').bounding_box()
            check(f'{width}: all four map concepts fit without clipping',
                  last['y'] + last['height'] <= tree['y'] + tree['height'] + 1)
            read = page.locator('#readToggle').bounding_box()
            check(f'{width}: reading toggle stays reachable', 0 <= read['x'] and read['x'] + read['width'] <= width)
        shot(f'map-{width}.png')
        before = paper_geometry(page)
        map_before = page.locator('#mapPanel').bounding_box()
        page.locator('#layerToggle').click()
        opened=paper_geometry(page)
        check(f'{width}: layers do not change paper geometry, transform or reading position with map open', opened == before)
        geometry_evidence.append({'style':'blend','viewport':[width,height],'map_open':True,'before':before,'open':opened})
        check(f'{width}: layers do not close or rearrange the independent map', page.locator('#mapPanel').is_visible() and page.locator('#mapPanel').bounding_box() == map_before)
        page.locator('#closeLayers').click()
        check(f'{width}: closing layers retains map and source geometry', paper_geometry(page) == before and page.locator('#mapPanel').is_visible())
        if width == 375:
            page.locator('#closeMap').click()
            page.locator('#layerToggle').click()
            shot('narrow-layers.png')
            page.locator('#closeLayers').click()

    verify_layer_interactions(page, check)

    touch = browser.new_page(viewport={'width':375,'height':800}, has_touch=True)
    touch.on('pageerror', lambda error: errors.append(str(error)))
    touch.on('request', lambda request: requests.append(request.url))
    touch.goto(URL,wait_until='networkidle')
    touch_before=paper_geometry(touch)
    touch.locator('#layerToggle').tap()
    check('Touch: layer sheet keeps paper viewport and transform fixed',paper_geometry(touch)==touch_before)
    touch.get_by_role('button',name='显示推导草稿',exact=True).tap()
    check('Touch: layer controls work without creating ink behind the sheet',touch.locator('#draftNote').is_visible() and touch.locator('#ink path').count()==0)
    touch.touchscreen.tap(55,260)
    touch.wait_for_function("!document.getElementById('layerPanel').open")
    check('Touch: outside tap dismisses sheet without an accidental stroke',touch.locator('#ink path').count()==0 and paper_geometry(touch)==touch_before)
    touch.touchscreen.tap(55,260)
    check('Touch: trial writing resumes after the dismissal tap',touch.locator('#ink path').count()==1)
    touch.close()

    page.emulate_media(reduced_motion='reduce')
    page.locator('.styles [data-style=liquid]').click()
    check('Reduced motion disables spring transitions',
          page.locator('.styles button').first.evaluate("e=>getComputedStyle(e).transitionDuration==='0s'"))
    check('No JavaScript runtime errors', not errors)
    check('Application makes no external resource or data requests',
          all(url.startswith(URL) for url in requests))
    browser.close()

report = {'browser': 'System Chromium via Playwright, cloud Linux',
          'rendering': 'Web, not native Android', 'url': URL,
          'passed': len(checks), 'checks': checks, 'runtime_errors': errors,
          'note': 'Synthetic content only. No device, native latency, pressure, palm rejection or persistence claims.'}
(OUT / 'layer-geometry.json').write_text(json.dumps(geometry_evidence,ensure_ascii=False,indent=2))
(OUT / 'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
print(json.dumps({'passed': len(checks), 'runtime_errors': errors}, ensure_ascii=False))
