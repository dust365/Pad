#import <Foundation/Foundation.h>

@interface VirtualDisplayController : NSObject
@property (nonatomic, readonly) unsigned int displayID;
- (BOOL)ensureDisplayedNamed:(NSString *)name
                       width:(unsigned int)width
                      height:(unsigned int)height
                       error:(NSString * _Nullable * _Nullable)error;
@end
