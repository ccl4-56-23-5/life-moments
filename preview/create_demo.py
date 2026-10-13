"""Generate 35 illustrative demo images, containing no personal photographs."""
import base64, io, json, math
from pathlib import Path
from PIL import Image, ImageDraw

def create_demo(destination):
    photos = []
    for n in range(35):
        w, h = [(640, 440), (432, 640), (640, 480), (640, 360)][n % 4]
        image = Image.new('RGB', (w, h), '#daeaf5')
        draw = ImageDraw.Draw(image)
        colors = [('#d5e8f5', '#99bad4', '#5a91b4'), ('#f6ddce', '#d4a992', '#a77c68'), ('#e0ead9', '#adbea0', '#688f7a')][n % 3]
        draw.rectangle((0, 0, w, h), fill=colors[0])
        draw.ellipse((w*.7, h*.12, w*.85, h*.12+w*.15), fill='#fff1c8')
        draw.polygon([(0,h*.68),(w*.22,h*.32),(w*.53,h*.73),(w*.78,h*.4),(w,h*.64),(w,h),(0,h)],fill=colors[1])
        draw.polygon([(0,h*.8),(w*.34,h*.56),(w*.63,h*.83),(w,h*.6),(w,h),(0,h)],fill=colors[2])
        # Small geometric figures make avatar / group-photo examples recognizable.
        for person in range(1 + n % 4):
            x=w*(.2+person*.16); y=h*.64; r=w*.024
            draw.ellipse((x-r,y-r*3,x+r,y-r),fill='#f8f3e8')
            draw.rounded_rectangle((x-r*1.2,y-r,x+r*1.2,y+r*3),radius=r,fill='#f8f3e8')
        output=io.BytesIO(); image.save(output,format='JPEG',quality=82)
        month=1+n//6; day=1+n%6*3
        date=f'2022-{month:02d}-{day:02d}'
        name=('生日聚会' if n%7==0 else ['校园时光','湖边散步','一起旅行'][n%3])+f'-示例{n+1:02d}.jpg'
        photos.append({'id':n+1,'name':name,'date':date,'time':date.replace('-',':')+' 12:00:00','month':date[:7], 'width':w,'height':h,'size':len(output.getvalue()),'dateSource':'示例','camera':'程序生成插画','gps':[108.95,34.26] if n%2==0 else [114.3,30.6], 'image':'data:image/jpeg;base64,'+base64.b64encode(output.getvalue()).decode('ascii')})
    data={'source':'示例照片','count':35,'samples':35,'photos':photos}
    Path(destination).write_text(json.dumps(data,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    return data

if __name__ == '__main__':
    create_demo(Path(__file__).with_name('demo-data.json'))
