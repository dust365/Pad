#import "VirtualDisplay.h"
#import <AppKit/AppKit.h>
#import <CoreGraphics/CoreGraphics.h>
#import <objc/message.h>
#import <objc/runtime.h>

@implementation VirtualDisplayController {
    id _object;
    unsigned int _displayID;
}

- (unsigned int)displayID {
    return _displayID;
}

- (BOOL)ensureDisplayedNamed:(NSString *)name
                       width:(unsigned int)width
                      height:(unsigned int)height
                       error:(NSString **)error {
    if (name.length == 0 || width < 2 || height < 2) {
        if (error) {
            *error = @"虚拟显示器的名称或分辨率无效。";
        }
        return NO;
    }
    unsigned int minPixels = width * 2 - 32;
    unsigned int existing = [self displayNamed:name minPixelsWide:minPixels];
    if (existing != 0) {
        _displayID = existing;
        return YES;
    }
    if (_object != nil && _displayID != 0 && CGDisplayIsOnline(_displayID)) {
        CGDisplayModeRef current = CGDisplayCopyDisplayMode(_displayID);
        size_t pixelsWide = current == NULL ? 0 : CGDisplayModeGetPixelWidth(current);
        if (current != NULL) {
            CGDisplayModeRelease(current);
        }
        if (pixelsWide >= minPixels) {
            return YES;
        }
    }

    Class descriptorClass = NSClassFromString(@"CGVirtualDisplayDescriptor");
    Class modeClass = NSClassFromString(@"CGVirtualDisplayMode");
    Class settingsClass = NSClassFromString(@"CGVirtualDisplaySettings");
    Class displayClass = NSClassFromString(@"CGVirtualDisplay");
    if (descriptorClass == Nil || modeClass == Nil || settingsClass == Nil || displayClass == Nil) {
        if (error) {
            *error = @"系统没有 CGVirtualDisplay，无法创建虚拟显示器。";
        }
        return NO;
    }

    id descriptor = [self makeInstance:descriptorClass selector:"init"];
    [self setString:name selector:"setName:" object:descriptor];
    // 点大小是面板分辨率，像素是 2 倍。界面按 2 倍绘制后再缩回平板。
    [self setUnsigned:width * 2 selector:"setMaxPixelsWide:" object:descriptor];
    [self setUnsigned:height * 2 selector:"setMaxPixelsHigh:" object:descriptor];
    [self setUnsigned:0x3456 selector:"setVendorID:" object:descriptor];
    [self setUnsigned:[self productForName:name] selector:"setProductID:" object:descriptor];
    // U03H1 必须继续用 0x03B1。这个序列号被系统记住成 2 倍屏，换掉会变回糊的 1 倍。
    [self setUnsigned:[self serialForName:name] selector:"setSerialNum:" object:descriptor];
    double mmWide = 220.0 * (double)width / 1024.0;
    double mmHigh = 129.0 * (double)height / 600.0;
    ((void (*)(id, SEL, CGSize))objc_msgSend)(descriptor, sel_getUid("setSizeInMillimeters:"), CGSizeMake(mmWide, mmHigh));
    ((void (*)(id, SEL, dispatch_queue_t))objc_msgSend)(descriptor, sel_getUid("setDispatchQueue:"), dispatch_get_main_queue());

    id modeAlloc = ((id (*)(Class, SEL))objc_msgSend)(modeClass, sel_getUid("alloc"));
    id mode = ((id (*)(id, SEL, unsigned int, unsigned int, double))objc_msgSend)(
        modeAlloc, sel_getUid("initWithWidth:height:refreshRate:"), width, height, 60.0);
    if (mode == nil) {
        if (error) {
            *error = [NSString stringWithFormat:@"无法创建 %u×%u 显示模式。", width, height];
        }
        return NO;
    }

    id settings = [self makeInstance:settingsClass selector:"init"];
    ((void (*)(id, SEL, unsigned int))objc_msgSend)(settings, sel_getUid("setHiDPI:"), 1);
    ((void (*)(id, SEL, id))objc_msgSend)(settings, sel_getUid("setModes:"), @[mode]);

    id displayAlloc = ((id (*)(Class, SEL))objc_msgSend)(displayClass, sel_getUid("alloc"));
    id display = ((id (*)(id, SEL, id))objc_msgSend)(displayAlloc, sel_getUid("initWithDescriptor:"), descriptor);
    if (display == nil) {
        if (error) {
            *error = @"虚拟显示器创建失败。";
        }
        return NO;
    }
    BOOL applied = ((BOOL (*)(id, SEL, id))objc_msgSend)(display, sel_getUid("applySettings:"), settings);
    unsigned int displayID = ((unsigned int (*)(id, SEL))objc_msgSend)(display, sel_getUid("displayID"));
    if (!applied || displayID == 0) {
        if (error) {
            *error = @"系统拒绝创建虚拟显示器。";
        }
        return NO;
    }

    _object = display;
    _displayID = displayID;
    [self waitUntilOnline:displayID minPixelsWide:minPixels];
    [self placeWithoutOverlap:displayID];
    NSLog(@"PadScreen display %@ %ux%u id %u", name, width, height, displayID);
    return YES;
}

- (unsigned int)serialForName:(NSString *)name {
    if ([name isEqualToString:@"U03H1"]) {
        return 0x03B1;
    }
    if ([name isEqualToString:@"K701BC"]) {
        return 0x03C1;
    }
    unsigned int serial = 2166136261u;
    for (NSUInteger i = 0; i < name.length; i++) {
        serial ^= [name characterAtIndex:i];
        serial *= 16777619u;
    }
    return 0x1000u | (serial & 0x0FFFu);
}

- (unsigned int)productForName:(NSString *)name {
    if ([name isEqualToString:@"U03H1"]) {
        return 0x0001;
    }
    if ([name isEqualToString:@"K701BC"]) {
        return 0x0002;
    }
    return 0x0003;
}

- (id)makeInstance:(Class)cls selector:(const char *)selector {
    id object = ((id (*)(Class, SEL))objc_msgSend)(cls, sel_getUid("alloc"));
    return ((id (*)(id, SEL))objc_msgSend)(object, sel_getUid(selector));
}

- (void)setUnsigned:(unsigned int)value selector:(const char *)selector object:(id)object {
    ((void (*)(id, SEL, unsigned int))objc_msgSend)(object, sel_getUid(selector), value);
}

- (void)setString:(NSString *)value selector:(const char *)selector object:(id)object {
    ((void (*)(id, SEL, id))objc_msgSend)(object, sel_getUid(selector), value);
}

- (unsigned int)displayNamed:(NSString *)name minPixelsWide:(unsigned int)minPixelsWide {
    for (NSScreen *screen in [NSScreen screens]) {
        if (![screen.localizedName isEqualToString:name]) {
            continue;
        }
        NSNumber *number = screen.deviceDescription[@"NSScreenNumber"];
        if (number == nil) {
            continue;
        }
        CGDirectDisplayID displayID = number.unsignedIntValue;
        CGDisplayModeRef mode = CGDisplayCopyDisplayMode(displayID);
        size_t pixelsWide = mode == NULL ? 0 : CGDisplayModeGetPixelWidth(mode);
        if (mode != NULL) {
            CGDisplayModeRelease(mode);
        }
        if (pixelsWide >= minPixelsWide) {
            return displayID;
        }
    }
    return 0;
}

- (void)waitUntilOnline:(unsigned int)displayID minPixelsWide:(unsigned int)minPixelsWide {
    for (int i = 0; i < 30; i++) {
        if (CGDisplayIsOnline(displayID)) {
            CGDisplayModeRef mode = CGDisplayCopyDisplayMode(displayID);
            size_t pixelsWide = mode == NULL ? 0 : CGDisplayModeGetPixelWidth(mode);
            if (mode != NULL) {
                CGDisplayModeRelease(mode);
            }
            if (pixelsWide >= minPixelsWide) {
                return;
            }
        }
        usleep(100000);
    }
}

- (void)placeWithoutOverlap:(unsigned int)displayID {
    CGRect bounds = CGDisplayBounds(displayID);
    CGFloat rightEdge = 0;
    BOOL overlaps = NO;
    for (NSScreen *screen in [NSScreen screens]) {
        NSNumber *number = screen.deviceDescription[@"NSScreenNumber"];
        if (number == nil) {
            continue;
        }
        CGDirectDisplayID other = number.unsignedIntValue;
        if (other == displayID) {
            continue;
        }
        CGRect otherBounds = CGDisplayBounds(other);
        rightEdge = MAX(rightEdge, CGRectGetMaxX(otherBounds));
        CGRect overlap = CGRectIntersection(bounds, otherBounds);
        if (!CGRectIsNull(overlap) && overlap.size.width >= 8 && overlap.size.height >= 8) {
            overlaps = YES;
        }
    }
    if (!overlaps) {
        return;
    }
    CGRect mainBounds = CGDisplayBounds(CGMainDisplayID());
    CGDisplayConfigRef config = NULL;
    if (CGBeginDisplayConfiguration(&config) != kCGErrorSuccess) {
        return;
    }
    CGConfigureDisplayOrigin(config, displayID, (int)rightEdge, (int)CGRectGetMinY(mainBounds));
    CGCompleteDisplayConfiguration(config, kCGConfigureForSession);
}

@end
