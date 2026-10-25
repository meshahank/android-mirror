export type Rect={left:number;top:number;width:number;height:number};
/** Returns normalized screen coordinates or null when the pointer is in video letterboxing. */
export function videoPoint(clientX:number,clientY:number,box:Rect,videoWidth:number,videoHeight:number):{x:number;y:number}|null{
 if(videoWidth<=0||videoHeight<=0||box.width<=0||box.height<=0)return null;
 const scale=Math.min(box.width/videoWidth,box.height/videoHeight), w=videoWidth*scale,h=videoHeight*scale,left=box.left+(box.width-w)/2,top=box.top+(box.height-h)/2;
 if(clientX<left||clientX>left+w||clientY<top||clientY>top+h)return null;
 return{x:(clientX-left)/w,y:(clientY-top)/h};
}
